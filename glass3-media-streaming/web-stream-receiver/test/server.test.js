import assert from 'node:assert/strict';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { WebSocket } from 'ws';

import { createServer } from '../src/server.js';

function waitForOpen(socket) {
  return new Promise((resolve, reject) => {
    socket.once('open', resolve);
    socket.once('error', reject);
  });
}

function nextJson(socket) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('timed out waiting for WebSocket message')), 2_000);
    socket.once('message', (data) => {
      clearTimeout(timer);
      resolve(JSON.parse(data.toString()));
    });
    socket.once('error', (error) => {
      clearTimeout(timer);
      reject(error);
    });
  });
}

test('server exposes health, static content, and returns 404 for an unknown path', async (t) => {
  const publicDir = await mkdtemp(path.join(tmpdir(), 'glass-webrtc-public-'));
  await writeFile(path.join(publicDir, 'index.html'), '<h1>receiver</h1>', 'utf8');
  const server = createServer({ host: '127.0.0.1', port: 0, publicDir });
  const address = await server.start();
  t.after(async () => {
    await server.stop();
    await rm(publicDir, { recursive: true, force: true });
  });

  const health = await fetch(`http://127.0.0.1:${address.port}/health`);
  assert.equal(health.status, 200);
  assert.deepEqual(await health.json(), {
    status: 'ok',
    service: 'glass3-media-receiver',
  });

  const index = await fetch(`http://127.0.0.1:${address.port}/`);
  assert.equal(index.status, 200);
  assert.equal(await index.text(), '<h1>receiver</h1>');

  const missing = await fetch(`http://127.0.0.1:${address.port}/missing`);
  assert.equal(missing.status, 404);
});

test('default receiver page exposes the formal product name', async (t) => {
  const server = createServer({
    host: '127.0.0.1',
    port: 0,
    publicDir: new URL('../public/', import.meta.url),
  });
  const address = await server.start();
  t.after(() => server.stop());

  const response = await fetch(`http://127.0.0.1:${address.port}/`);
  const html = await response.text();

  assert.equal(response.status, 200);
  assert.match(html, /<title>Glass3 Media Receiver<\/title>/);
  assert.match(html, /Glass3 · MEDIA STREAMING/);
  assert.match(html, /Glass3 音视频接收端/);
  assert.match(html, /id="pageAddress"/);
  assert.match(html, /id="signalingAddress"/);
  assert.doesNotMatch(html, /\bTEST\b|测试/);
});

test('malformed and oversized WebSocket messages return bounded protocol errors', async (t) => {
  const publicDir = await mkdtemp(path.join(tmpdir(), 'glass3-media-public-'));
  await writeFile(path.join(publicDir, 'index.html'), 'receiver', 'utf8');
  const server = createServer({ host: '127.0.0.1', port: 0, publicDir });
  const address = await server.start();
  const socket = new WebSocket(`ws://127.0.0.1:${address.port}/ws`);
  t.after(async () => {
    socket.close();
    await server.stop();
    await rm(publicDir, { recursive: true, force: true });
  });

  await waitForOpen(socket);
  let response = nextJson(socket);
  socket.send('{');
  assert.match((await response).message, /JSON/);

  response = nextJson(socket);
  socket.send(JSON.stringify({ type: 'offer', sdp: 'x'.repeat(65_537) }));
  assert.deepEqual(await response, { type: 'error', message: 'invalid message size' });
});

test('sender and receiver become ready and signaling is relayed only to the peer', async (t) => {
  const publicDir = await mkdtemp(path.join(tmpdir(), 'glass-webrtc-public-'));
  await writeFile(path.join(publicDir, 'index.html'), 'receiver', 'utf8');
  const server = createServer({ host: '127.0.0.1', port: 0, publicDir });
  const address = await server.start();
  const receiver = new WebSocket(`ws://127.0.0.1:${address.port}/ws`);
  const sender = new WebSocket(`ws://127.0.0.1:${address.port}/ws`);
  t.after(async () => {
    receiver.close();
    sender.close();
    await server.stop();
    await rm(publicDir, { recursive: true, force: true });
  });

  await Promise.all([waitForOpen(receiver), waitForOpen(sender)]);
  receiver.send(JSON.stringify({ type: 'join', roomId: 'default', role: 'receiver' }));
  sender.send(JSON.stringify({ type: 'join', roomId: 'default', role: 'sender' }));

  assert.deepEqual(await nextJson(receiver), { type: 'peer-ready', roomId: 'default' });
  assert.deepEqual(await nextJson(sender), { type: 'peer-ready', roomId: 'default' });

  const receiverMessage = nextJson(receiver);
  sender.send(JSON.stringify({ type: 'offer', roomId: 'default', sdp: 'v=0\r\n' }));
  assert.deepEqual(await receiverMessage, { type: 'offer', roomId: 'default', sdp: 'v=0\r\n' });
});

test('first WebSocket message must be join', async (t) => {
  const publicDir = await mkdtemp(path.join(tmpdir(), 'glass-webrtc-public-'));
  await writeFile(path.join(publicDir, 'index.html'), 'receiver', 'utf8');
  const server = createServer({ host: '127.0.0.1', port: 0, publicDir });
  const address = await server.start();
  const socket = new WebSocket(`ws://127.0.0.1:${address.port}/ws`);
  t.after(async () => {
    socket.close();
    await server.stop();
    await rm(publicDir, { recursive: true, force: true });
  });

  await waitForOpen(socket);
  const errorMessage = nextJson(socket);
  socket.send(JSON.stringify({ type: 'offer', roomId: 'default', sdp: 'v=0' }));
  assert.deepEqual(await errorMessage, { type: 'error', message: 'join required' });
});
