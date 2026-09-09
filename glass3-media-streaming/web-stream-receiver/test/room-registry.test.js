import assert from 'node:assert/strict';
import test from 'node:test';

import { RoomRegistry } from '../src/room-registry.js';

test('one sender and one receiver are paired in the same room', () => {
  const registry = new RoomRegistry();
  const sender = {};
  const receiver = {};

  assert.deepEqual(registry.join('default', 'receiver', receiver), { peer: null });
  assert.deepEqual(registry.join('default', 'sender', sender), { peer: receiver });
  assert.equal(registry.peerOf(sender), receiver);
  assert.equal(registry.peerOf(receiver), sender);
});

test('a receiver joining after the sender is paired immediately', () => {
  const registry = new RoomRegistry();
  const sender = {};
  const receiver = {};

  assert.deepEqual(registry.join('default', 'sender', sender), { peer: null });
  assert.deepEqual(registry.join('default', 'receiver', receiver), { peer: sender });
  assert.equal(registry.peerOf(sender), receiver);
});

test('a reloaded receiver can rejoin while the sender remains connected', () => {
  const registry = new RoomRegistry();
  const sender = {};
  const firstReceiver = {};
  const reloadedReceiver = {};
  registry.join('default', 'sender', sender);
  registry.join('default', 'receiver', firstReceiver);

  assert.deepEqual(registry.leave(firstReceiver), { peer: sender });
  assert.deepEqual(registry.join('default', 'receiver', reloadedReceiver), { peer: sender });
  assert.equal(registry.peerOf(sender), reloadedReceiver);
});

test('a second sender is rejected without replacing the first sender', () => {
  const registry = new RoomRegistry();
  const first = {};
  const second = {};
  const receiver = {};
  registry.join('default', 'sender', first);
  registry.join('default', 'receiver', receiver);

  assert.throws(() => registry.join('default', 'sender', second), /sender already joined/);
  assert.equal(registry.peerOf(receiver), first);
});

test('a second receiver is rejected without replacing the first receiver', () => {
  const registry = new RoomRegistry();
  const sender = {};
  const first = {};
  const second = {};
  registry.join('default', 'sender', sender);
  registry.join('default', 'receiver', first);

  assert.throws(() => registry.join('default', 'receiver', second), /receiver already joined/);
  assert.equal(registry.peerOf(sender), first);
});

test('leaving returns the peer and removes membership', () => {
  const registry = new RoomRegistry();
  const sender = {};
  const receiver = {};
  registry.join('default', 'sender', sender);
  registry.join('default', 'receiver', receiver);

  assert.deepEqual(registry.leave(sender), { peer: receiver });
  assert.equal(registry.peerOf(sender), null);
  assert.equal(registry.peerOf(receiver), null);
  assert.deepEqual(registry.leave(sender), { peer: null });
});

test('an empty room is removed and can be reused', () => {
  const registry = new RoomRegistry();
  const first = {};
  const second = {};
  registry.join('default', 'sender', first);
  registry.leave(first);

  assert.deepEqual(registry.join('default', 'sender', second), { peer: null });
});
