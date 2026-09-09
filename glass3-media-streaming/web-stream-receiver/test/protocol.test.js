import assert from 'node:assert/strict';
import test from 'node:test';

import { encodeEnvelope, parseEnvelope } from '../src/protocol.js';

const validMessages = [
  { type: 'join', roomId: 'default', role: 'sender' },
  { type: 'peer-ready', roomId: 'default' },
  { type: 'offer', roomId: 'room_1', sdp: 'v=0\r\nm=video' },
  { type: 'answer', roomId: 'room-2', sdp: 'v=0\r\nm=audio' },
  {
    type: 'ice-candidate',
    roomId: 'default',
    candidate: {
      sdpMid: '0',
      sdpMLineIndex: 0,
      candidate: 'candidate:1 1 udp 1 192.168.1.2 50000 typ host',
    },
  },
  { type: 'leave', roomId: 'default' },
  { type: 'error', roomId: 'default', message: 'receiver already joined' },
];

test('supported signaling messages round-trip without changing their payload', () => {
  for (const message of validMessages) {
    assert.deepEqual(parseEnvelope(encodeEnvelope(message)), message);
  }
});

test('message larger than 64 KiB is rejected before JSON parsing', () => {
  const oversized = JSON.stringify({ type: 'offer', sdp: 'x'.repeat(65_537) });
  assert.throws(() => parseEnvelope(oversized), /invalid message size/);
});

test('unknown message type is rejected', () => {
  assert.throws(() => parseEnvelope('{"type":"execute"}'), /invalid message type/);
});

test('room id is restricted to 1-32 safe characters', () => {
  for (const roomId of ['', 'contains space', 'a'.repeat(33), '../other']) {
    assert.throws(
      () => parseEnvelope(JSON.stringify({ type: 'join', roomId, role: 'sender' })),
      /invalid roomId/,
    );
  }
  assert.equal(parseEnvelope('{"type":"join","roomId":"A_b-9","role":"receiver"}').roomId, 'A_b-9');
});

test('join role must be sender or receiver', () => {
  assert.throws(
    () => parseEnvelope('{"type":"join","roomId":"default","role":"admin"}'),
    /invalid role/,
  );
});

test('malformed JSON is rejected', () => {
  assert.throws(() => parseEnvelope('{'), SyntaxError);
});
