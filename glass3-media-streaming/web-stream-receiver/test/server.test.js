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

function nextMessages(socket, count) {
  return new Promise((resolve, reject) => {
    const messages = [];
    const timer = setTimeout(() => {
      socket.off('message', onMessage);
      reject(new Error(`expected ${count} messages, received ${JSON.stringify(messages)}`));
    }, 2_000);
    function onMessage(data) {
      messages.push(JSON.parse(data.toString()));
      if (messages.length === count) {
        clearTimeout(timer);
        socket.off('message', onMessage);
        resolve(messages);
      }
    }
    socket.on('message', onMessage);
  });
}

function waitForClose(socket) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('timed out waiting for WebSocket close')), 2_000);
    socket.once('close', (code, reason) => {
      clearTimeout(timer);
      resolve({ code, reason: reason.toString() });
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

test('a newer receiver takes over the room and the stale receiver is closed', async (t) => {
  const publicDir = await mkdtemp(path.join(tmpdir(), 'glass-webrtc-public-'));
  await writeFile(path.join(publicDir, 'index.html'), 'receiver', 'utf8');
  const server = createServer({ host: '127.0.0.1', port: 0, publicDir });
  const address = await server.start();
  const sender = new WebSocket(`ws://127.0.0.1:${address.port}/ws`);
  const staleReceiver = new WebSocket(`ws://127.0.0.1:${address.port}/ws`);
  const newerReceiver = new WebSocket(`ws://127.0.0.1:${address.port}/ws`);
  t.after(async () => {
    sender.close();
    staleReceiver.close();
    newerReceiver.close();
    await server.stop();
    await rm(publicDir, { recursive: true, force: true });
  });

  await Promise.all([waitForOpen(sender), waitForOpen(staleReceiver), waitForOpen(newerReceiver)]);
  sender.send(JSON.stringify({ type: 'join', roomId: 'default', role: 'sender' }));
  staleReceiver.send(JSON.stringify({ type: 'join', roomId: 'default', role: 'receiver' }));
  await Promise.all([nextJson(sender), nextJson(staleReceiver)]);

  const staleClosed = waitForClose(staleReceiver);
  const senderRestart = nextMessages(sender, 2);
  const receiverReady = nextJson(newerReceiver);
  newerReceiver.send(JSON.stringify({ type: 'join', roomId: 'default', role: 'receiver' }));

  assert.deepEqual(await staleClosed, { code: 1000, reason: 'replaced by newer receiver' });
  assert.deepEqual(await senderRestart, [
    { type: 'leave', roomId: 'default' },
    { type: 'peer-ready', roomId: 'default' },
  ]);
  assert.deepEqual(await receiverReady, { type: 'peer-ready', roomId: 'default' });

  // The retained sender can now negotiate with the new receiver; delayed close
  // of the replaced socket must not emit a second leave into the new session.
  const answer = nextJson(sender);
  newerReceiver.send(JSON.stringify({ type: 'answer', roomId: 'default', sdp: 'new-answer' }));
  assert.deepEqual(await answer, { type: 'answer', roomId: 'default', sdp: 'new-answer' });
});

test('a newer sender tells the retained receiver to discard its old peer before readiness', async (t) => {
  const server = createServer({ host: '127.0.0.1', port: 0, publicDir: new URL('../public/', import.meta.url) });
  const address = await server.start();
  const receiver = new WebSocket(`ws://127.0.0.1:${address.port}/ws`);
  const oldSender = new WebSocket(`ws://127.0.0.1:${address.port}/ws`);
  const newSender = new WebSocket(`ws://127.0.0.1:${address.port}/ws`);
  t.after(() => server.stop());
  await Promise.all([waitForOpen(receiver), waitForOpen(oldSender), waitForOpen(newSender)]);
  const initialReady = Promise.all([nextJson(receiver), nextJson(oldSender)]);
  receiver.send(JSON.stringify({ type: 'join', roomId: 'default', role: 'receiver' }));
  oldSender.send(JSON.stringify({ type: 'join', roomId: 'default', role: 'sender' }));
  await initialReady;
  const restart = nextMessages(receiver, 2);
  const oldClosed = waitForClose(oldSender);
  newSender.send(JSON.stringify({ type: 'join', roomId: 'default', role: 'sender' }));
  assert.deepEqual(await restart, [
    { type: 'leave', roomId: 'default' },
    { type: 'peer-ready', roomId: 'default' },
  ]);
  await oldClosed;
  const offer = nextJson(receiver);
  newSender.send(JSON.stringify({ type: 'offer', roomId: 'default', sdp: 'fresh-offer' }));
  assert.deepEqual(await offer, { type: 'offer', roomId: 'default', sdp: 'fresh-offer' });
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
