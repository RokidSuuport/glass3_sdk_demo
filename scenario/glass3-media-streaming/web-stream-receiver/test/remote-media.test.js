import assert from 'node:assert/strict';
import test from 'node:test';

test('audio and video tracks remain attached when they arrive separately', async () => {
  const remoteMedia = await import('../public/remote-media.js').catch(() => ({}));
  assert.equal(typeof remoteMedia.attachRemoteTrack, 'function');

  const audioTrack = { id: 'audio-1', kind: 'audio' };
  const videoTrack = { id: 'video-1', kind: 'video' };
  const tracks = [];
  const stream = {
    addTrack(track) {
      tracks.push(track);
    },
    getTracks() {
      return tracks;
    },
  };
  const mediaElement = { srcObject: null };

  remoteMedia.attachRemoteTrack(mediaElement, stream, audioTrack);
  remoteMedia.attachRemoteTrack(mediaElement, stream, videoTrack);

  assert.equal(mediaElement.srcObject, stream);
  assert.deepEqual(stream.getTracks(), [audioTrack, videoTrack]);
});
