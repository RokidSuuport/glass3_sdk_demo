export function attachRemoteTrack(mediaElement, stream, track) {
  if (!stream.getTracks().some((item) => item.id === track.id)) {
    stream.addTrack(track);
  }
  if (mediaElement.srcObject !== stream) {
    mediaElement.srcObject = stream;
  }
}
