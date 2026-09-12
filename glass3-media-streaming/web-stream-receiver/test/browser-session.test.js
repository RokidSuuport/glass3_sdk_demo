import assert from 'node:assert/strict';
import test from 'node:test';
import { browserHarness, deferred, flush } from './browser-harness.js';

test('a current offer drains queued ICE and sends its answer to the current socket', async () => {
  const browser = await browserHarness();
  const { socket, pc } = browser.start();
  const candidates = [];
  pc.addIceCandidate = async (candidate) => { candidates.push(candidate.candidate); };
  socket.message({ type: 'ice-candidate', candidate: { candidate: 'candidate:queued', sdpMid: '0', sdpMLineIndex: 0 } });
  socket.message({ type: 'offer', sdp: 'current-offer' });
  await flush();
  assert.deepEqual(candidates, ['candidate:queued']);
  assert.deepEqual(socket.sent.filter((message) => message.type === 'answer'), [
    { type: 'answer', roomId: 'default', sdp: 'current-answer' },
  ]);
});

test('a rejected old offer cannot close a manually reconnected receiver', async () => {
  const browser = await browserHarness();
  const old = browser.start();
  const pending = deferred();
  old.pc.setRemoteDescription = () => pending.promise;
  old.socket.message({ type: 'offer', sdp: 'old-offer' });
  browser.stop();
  const current = browser.start();
  pending.reject(new Error('old peer closed'));
  await flush();
  assert.equal(current.pc.closed, false);
  assert.equal(browser.get('statusBadge').dataset.phase, 'connecting');
  assert.equal(browser.get('errorMessage').hidden, true);
});

test('a late old answer is never sent into the replacement peer on the same socket', async () => {
  const browser = await browserHarness();
  const old = browser.start();
  const pending = deferred();
  old.pc.createAnswer = () => pending.promise;
  old.socket.message({ type: 'offer', sdp: 'old-offer' });
  await flush();
  old.socket.message({ type: 'leave' });
  old.socket.message({ type: 'peer-ready' });
  pending.resolve({ type: 'answer', sdp: 'stale-answer' });
  await flush();
  assert.equal(old.socket.sent.some((message) => message.sdp === 'stale-answer'), false);
});

test('an old offer rejection cannot close the replacement peer on the same signaling socket', async () => {
  const browser = await browserHarness();
  const { socket, pc } = browser.start();
  const pending = deferred();
  pc.setRemoteDescription = () => pending.promise;
  socket.message({ type: 'offer', sdp: 'old-offer' });
  socket.message({ type: 'leave' });
  socket.message({ type: 'peer-ready' });
  const current = browser.peers.at(-1);
  pending.reject(new Error('obsolete offer rejected'));
  await flush();
  assert.equal(current.closed, false);
  assert.equal(browser.get('statusBadge').dataset.phase, 'connecting');
});

test('queued events from an old socket cannot join or fail a new connection', async () => {
  const browser = await browserHarness();
  const old = browser.start();
  const lateOpen = old.socket.onopen;
  const lateError = old.socket.onerror;
  const lateMessage = old.socket.onmessage;
  browser.stop();
  const current = browser.start();
  lateOpen();
  lateError();
  lateMessage({ data: JSON.stringify({ type: 'leave' }) });
  await flush();
  assert.equal(current.pc.closed, false);
  assert.equal(current.socket.sent.filter((message) => message.type === 'join').length, 1);
});

for (const outcome of ['resolve', 'reject']) {
  test(`late stats ${outcome} cannot change or close the next peer`, async () => {
    const browser = await browserHarness();
    const old = browser.start();
    const pending = deferred();
    old.pc.getStats = () => pending.promise;
    old.pc.connect();
    browser.stop();
    const current = browser.start();
    if (outcome === 'resolve') pending.resolve(new Map([['v', {
      id: 'v', type: 'inbound-rtp', kind: 'video', timestamp: 1000,
      bytesReceived: 123, packetsLost: 99, frameWidth: 640, frameHeight: 360,
    }]]));
    else pending.reject(new Error('old stats closed'));
    await flush();
    assert.equal(current.pc.closed, false);
    assert.equal(browser.get('resolutionMetric').textContent, '—');
    assert.equal(browser.get('packetLossMetric').textContent, '—');
  });
}

test('ICE and a video track leave the placeholder until a frame is presented', async () => {
  const browser = await browserHarness();
  const { pc } = browser.start();
  pc.track('video');
  pc.connect();
  await flush();
  assert.equal(browser.get('videoPlaceholder').hidden, false);
  assert.equal(browser.get('statusBadge').dataset.phase, 'waiting-media');
  assert.equal(browser.get('fullscreenButton').disabled, true);
  browser.frame();
  assert.equal(browser.get('videoPlaceholder').hidden, true);
  assert.equal(browser.get('statusBadge').dataset.phase, 'video');
  assert.equal(browser.get('fullscreenButton').disabled, false);
});

test('audio-only needs incoming bytes and keeps an explanatory no-video placeholder', async () => {
  const browser = await browserHarness();
  const { pc } = browser.start();
  pc.track('audio');
  pc.connect();
  await flush();
  assert.equal(browser.get('statusBadge').dataset.phase, 'waiting-media');
  pc.stats = new Map([['a', {
    id: 'a', type: 'inbound-rtp', kind: 'audio', bytesReceived: 320,
    packetsReceived: 1, packetsLost: 0, timestamp: 1000,
  }]]);
  await browser.tick();
  assert.equal(browser.get('statusBadge').dataset.phase, 'audio');
  assert.equal(browser.get('videoPlaceholder').hidden, false);
  assert.match(browser.get('videoPlaceholder').querySelector('strong').textContent, /音频/);
  assert.match(browser.get('videoPlaceholder').querySelector('span').textContent, /无视频|[不未]发送视频/);
  assert.equal(browser.get('fullscreenButton').disabled, true);
  assert.equal(browser.get('remoteVideo').muted, true);
  browser.get('audioButton').emit('click');
  assert.equal(browser.get('remoteVideo').muted, false);
});

test('stalled video becomes waiting-media instead of claiming a live picture', async () => {
  const browser = await browserHarness();
  const { pc } = browser.start();
  pc.track('video');
  pc.connect();
  browser.frame();
  await browser.tick(4_000);
  assert.equal(browser.get('statusBadge').dataset.phase, 'waiting-media');
  assert.equal(browser.get('videoPlaceholder').hidden, false);
  assert.match(browser.get('videoPlaceholder').querySelector('strong').textContent, /中断|等待/);
  browser.frame();
  assert.equal(browser.get('statusBadge').dataset.phase, 'video');
});

test('fallback video detection needs decoded frames and displayable video data', async () => {
  const browser = await browserHarness();
  browser.get('remoteVideo').requestVideoFrameCallback = undefined;
  const { pc } = browser.start();
  pc.track('video');
  const report = {
    id: 'v', type: 'inbound-rtp', kind: 'video', timestamp: 1000,
    bytesReceived: 640, packetsLost: 0, framesDecoded: 1, frameWidth: 1280, frameHeight: 720,
  };
  pc.stats = new Map([['v', report]]);
  pc.connect();
  await flush();
  assert.equal(browser.get('videoPlaceholder').hidden, false);
  browser.get('remoteVideo').readyState = 2;
  browser.get('remoteVideo').videoWidth = 1280;
  report.framesDecoded = 2;
  await browser.tick();
  assert.equal(browser.get('videoPlaceholder').hidden, true);
  assert.equal(browser.get('statusBadge').dataset.phase, 'video');
});

test('audio stops claiming reception when inbound bytes stop increasing', async () => {
  const browser = await browserHarness();
  const { pc } = browser.start();
  pc.track('audio');
  pc.stats = new Map([['a', { id: 'a', type: 'inbound-rtp', kind: 'audio', timestamp: 1, bytesReceived: 320 }]]);
  pc.connect();
  await flush();
  assert.equal(browser.get('statusBadge').dataset.phase, 'audio');
  await browser.tick(4_000);
  assert.equal(browser.get('statusBadge').dataset.phase, 'waiting-media');
});

test('video-only autoplay rejection leaves an enabled user-gesture playback recovery', async () => {
  const browser = await browserHarness();
  const { pc } = browser.start();
  const video = browser.get('remoteVideo');
  video.play = () => Promise.reject(new Error('autoplay denied'));
  pc.track('video');
  pc.connect();
  await flush();
  assert.equal(browser.get('videoPlaceholder').hidden, false);
  assert.equal(browser.get('fullscreenButton').disabled, true);
  assert.equal(browser.get('audioButton').disabled, false);
  assert.equal(browser.get('audioButton').textContent, '播放视频');

  video.play = () => Promise.resolve();
  browser.get('audioButton').emit('click');
  await flush();
  await browser.tick();
  // No audio bytes are expected. The user gesture stays available while waiting
  // for the first video frame, and does not unmute a video-only element.
  assert.equal(video.muted, true);
  assert.equal(browser.get('audioButton').disabled, false);
  assert.equal(browser.get('audioButton').textContent, '播放视频');
  browser.frame();
  assert.equal(browser.get('statusBadge').dataset.phase, 'video');
  assert.equal(browser.get('videoPlaceholder').hidden, true);
});

test('an audio track permits an explicit playback gesture before its first bytes arrive', async () => {
  const browser = await browserHarness();
  const { pc } = browser.start();
  const video = browser.get('remoteVideo');
  video.play = () => Promise.reject(new Error('autoplay denied'));
  pc.track('audio');
  pc.connect();
  await flush();
  assert.equal(browser.get('statusBadge').dataset.phase, 'waiting-media');
  assert.equal(browser.get('audioButton').disabled, false);
  assert.equal(browser.get('audioButton').textContent, '开启声音');
  video.play = () => Promise.resolve();
  browser.get('audioButton').emit('click');
  await flush();
  assert.equal(video.muted, false);
});

test('an obsolete autoplay rejection cannot add a recovery action to a new empty peer', async () => {
  const browser = await browserHarness();
  const { pc } = browser.start();
  const pending = deferred();
  browser.get('remoteVideo').play = () => pending.promise;
  pc.track('video');
  browser.stop();
  const current = browser.start();
  pending.reject(new Error('obsolete autoplay rejection'));
  await flush();
  assert.equal(browser.get('audioButton').disabled, true);
  assert.equal(browser.get('statusBadge').dataset.phase, 'connecting');
  assert.equal(current.pc.closed, false);
});
