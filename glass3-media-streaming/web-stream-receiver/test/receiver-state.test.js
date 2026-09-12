import assert from 'node:assert/strict';
import test from 'node:test';

import {
  errorAction,
  initialReceiverState,
  reduceReceiverState,
  stateLabel,
} from '../public/receiver-state.js';

test('customer-facing labels describe the receiver state precisely', () => {
  assert.equal(stateLabel('waiting'), '等待眼镜端连接');
  assert.equal(stateLabel('streaming'), '正在接收音视频');
  assert.equal(stateLabel('unknown'), '状态未知');
});

test('common connection failures include a direct recovery action', () => {
  assert.match(errorAction('UNSUPPORTED_BROWSER'), /Chrome|Edge/);
  assert.match(errorAction('WEBSOCKET_FAILED'), /地址|服务/);
  assert.match(errorAction('receiver already joined'), /其他接收页面|关闭/);
});

test('receiver follows the expected connection path', () => {
  let state = initialReceiverState('default');
  assert.equal(state.phase, 'idle');

  state = reduceReceiverState(state, { type: 'SIGNALING_CONNECTING' });
  assert.equal(state.phase, 'signaling');
  state = reduceReceiverState(state, { type: 'SIGNALING_OPEN' });
  assert.equal(state.phase, 'waiting');
  state = reduceReceiverState(state, { type: 'PEER_READY' });
  assert.equal(state.phase, 'connecting');
  state = reduceReceiverState(state, { type: 'ICE_CONNECTED' });
  assert.equal(state.phase, 'waiting-media');
  state = reduceReceiverState(state, { type: 'MEDIA_STATUS', video: true, audio: true });
  assert.equal(state.phase, 'streaming');
});

test('failure exposes a readable error without losing the room', () => {
  const failed = reduceReceiverState(initialReceiverState('room_9'), {
    type: 'FAILED',
    message: 'ICE 连接失败',
  });

  assert.deepEqual(failed, {
    roomId: 'room_9',
    phase: 'error',
    error: 'ICE 连接失败',
  });
});

test('peer disconnect returns to waiting and preserves the room', () => {
  const streaming = { roomId: 'room-2', phase: 'streaming', error: '' };
  const waiting = reduceReceiverState(streaming, { type: 'PEER_LEFT' });

  assert.deepEqual(waiting, { roomId: 'room-2', phase: 'waiting', error: '' });
});

test('an interrupted peer connection is visible until ICE recovers', () => {
  const streaming = { roomId: 'room-2', phase: 'streaming', error: '' };
  const disconnected = reduceReceiverState(streaming, { type: 'DISCONNECTED' });
  assert.deepEqual(disconnected, { roomId: 'room-2', phase: 'disconnected', error: '' });

  const recovered = reduceReceiverState(disconnected, { type: 'ICE_CONNECTED' });
  assert.deepEqual(recovered, { roomId: 'room-2', phase: 'waiting-media', error: '' });
});

test('stopping the receiver returns to idle', () => {
  const waiting = { roomId: 'room-2', phase: 'waiting', error: '' };
  const idle = reduceReceiverState(waiting, { type: 'STOPPED' });

  assert.deepEqual(idle, { roomId: 'room-2', phase: 'idle', error: '' });
});

test('a non-fatal browser media error preserves the streaming connection', () => {
  const streaming = { roomId: 'room-2', phase: 'streaming', error: '' };
  const warned = reduceReceiverState(streaming, {
    type: 'NON_FATAL_ERROR',
    message: '浏览器阻止了声音播放',
  });

  assert.deepEqual(warned, {
    roomId: 'room-2',
    phase: 'streaming',
    error: '浏览器阻止了声音播放',
  });
});

test('unknown events do not mutate state', () => {
  const state = initialReceiverState('default');
  assert.equal(reduceReceiverState(state, { type: 'IGNORED' }), state);
});
