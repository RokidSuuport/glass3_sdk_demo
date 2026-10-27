import { createReadStream } from 'node:fs';
import { stat } from 'node:fs/promises';
import http from 'node:http';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { WebSocket, WebSocketServer } from 'ws';

import { encodeEnvelope, parseEnvelope } from './protocol.js';
import { RoomRegistry } from './room-registry.js';

const CONTENT_TYPES = new Map([
  ['.html', 'text/html; charset=utf-8'],
  ['.js', 'text/javascript; charset=utf-8'],
  ['.css', 'text/css; charset=utf-8'],
  ['.svg', 'image/svg+xml'],
  ['.png', 'image/png'],
]);

function normalizePublicDir(publicDir) {
  return publicDir instanceof URL ? fileURLToPath(publicDir) : path.resolve(publicDir);
}

function safeSend(socket, message) {
  if (socket?.readyState === WebSocket.OPEN) {
    socket.send(encodeEnvelope(message));
  }
}

function resolveStaticFile(publicDir, pathname) {
  const relativePath = pathname === '/' ? 'index.html' : decodeURIComponent(pathname).replace(/^\/+/, '');
  const root = path.resolve(publicDir);
  const candidate = path.resolve(root, relativePath);
  if (candidate !== root && !candidate.startsWith(`${root}${path.sep}`)) return null;
  return candidate;
}

export function createServer({ host = '0.0.0.0', port = 8080, publicDir }) {
  const registry = new RoomRegistry();
  const staticRoot = normalizePublicDir(publicDir);
  const webSocketServer = new WebSocketServer({ noServer: true });

  const httpServer = http.createServer(async (request, response) => {
    const requestUrl = new URL(request.url ?? '/', `http://${request.headers.host ?? 'localhost'}`);
    if (request.method === 'GET' && requestUrl.pathname === '/health') {
      response.writeHead(200, { 'content-type': 'application/json; charset=utf-8' });
      response.end(JSON.stringify({ status: 'ok', service: 'glass3-media-receiver' }));
      return;
    }
    if (request.method !== 'GET' && request.method !== 'HEAD') {
      response.writeHead(405, { allow: 'GET, HEAD' });
      response.end();
      return;
    }

    let filePath;
    try {
      filePath = resolveStaticFile(staticRoot, requestUrl.pathname);
    } catch {
      filePath = null;
    }
    if (!filePath) {
      response.writeHead(404);
      response.end('Not Found');
      return;
    }

    try {
      const fileStat = await stat(filePath);
      if (!fileStat.isFile()) throw new Error('not a file');
      const contentType = CONTENT_TYPES.get(path.extname(filePath)) ?? 'application/octet-stream';
      response.writeHead(200, {
        'content-type': contentType,
        'content-length': fileStat.size,
        'cache-control': 'no-store',
      });
      if (request.method === 'HEAD') {
        response.end();
      } else {
        createReadStream(filePath).pipe(response);
      }
    } catch {
      response.writeHead(404);
      response.end('Not Found');
    }
  });

  httpServer.on('upgrade', (request, socket, head) => {
    const requestUrl = new URL(request.url ?? '/', `http://${request.headers.host ?? 'localhost'}`);
    if (requestUrl.pathname !== '/ws') {
      socket.destroy();
      return;
    }
    webSocketServer.handleUpgrade(request, socket, head, (webSocket) => {
      webSocketServer.emit('connection', webSocket, request);
    });
  });

  webSocketServer.on('connection', (socket) => {
    let joined = false;

    socket.on('message', (data, isBinary) => {
      if (isBinary) {
        safeSend(socket, { type: 'error', message: 'text messages only' });
        return;
      }

      let message;
      try {
        message = parseEnvelope(data.toString());
      } catch (error) {
        safeSend(socket, { type: 'error', message: error.message });
        return;
      }

      if (!joined) {
        if (message.type !== 'join') {
          safeSend(socket, { type: 'error', message: 'join required' });
          return;
        }
        if (!message.roomId || !message.role) {
          safeSend(socket, { type: 'error', message: 'roomId and role required' });
          return;
        }
        try {
          const { peer, replaced } = registry.join(message.roomId, message.role, socket);
          joined = true;
          if (replaced) {
            // Registry membership is already revoked, so queued messages and the
            // old socket's eventual close cannot affect the replacement. Tell the
            // retained peer to discard its old SDP/ICE before starting a new pair.
            safeSend(peer, { type: 'leave', roomId: message.roomId });
            replaced.close(1000, `replaced by newer ${message.role}`);
          }
          if (peer) {
            const ready = { type: 'peer-ready', roomId: message.roomId };
            safeSend(peer, ready);
            safeSend(socket, ready);
          }
        } catch (error) {
          safeSend(socket, { type: 'error', message: error.message });
        }
        return;
      }

      if (message.type === 'leave') {
        socket.close(1000, 'client left');
        return;
      }
      if (!['offer', 'answer', 'ice-candidate'].includes(message.type)) {
        safeSend(socket, { type: 'error', message: 'message not allowed after join' });
        return;
      }
      const peer = registry.peerOf(socket);
      if (!peer) {
        safeSend(socket, { type: 'error', message: 'peer not ready' });
        return;
      }
      safeSend(peer, message);
    });

    socket.on('close', () => {
      const { peer } = registry.leave(socket);
      safeSend(peer, { type: 'leave' });
    });
  });

  return {
    start() {
      return new Promise((resolve, reject) => {
        const onError = (error) => reject(error);
        httpServer.once('error', onError);
        httpServer.listen(port, host, () => {
          httpServer.off('error', onError);
          resolve(httpServer.address());
        });
      });
    },
    stop() {
      for (const client of webSocketServer.clients) client.terminate();
      return new Promise((resolve, reject) => {
        webSocketServer.close(() => {
          httpServer.close((error) => {
            if (error) reject(error);
            else resolve();
          });
          httpServer.closeAllConnections?.();
        });
      });
    },
  };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const server = createServer({
    host: process.env.HOST ?? '0.0.0.0',
    port: Number(process.env.PORT ?? 8080),
    publicDir: new URL('../public/', import.meta.url),
  });
  server.start()
    .then(({ port: boundPort }) => console.log(`Glass3 Media Receiver: http://0.0.0.0:${boundPort}`))
    .catch((error) => {
      console.error(error);
      process.exitCode = 1;
    });
}
