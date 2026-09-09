import {
  errorAction,
  initialReceiverState,
  reduceReceiverState,
  stateLabel,
} from './receiver-state.js';
import { attachRemoteTrack } from './remote-media.js';

const roomId = 'default';
const elements = {
  start: document.querySelector('#startButton'),
  audio: document.querySelector('#audioButton'),
  fullscreen: document.querySelector('#fullscreenButton'),
  disconnect: document.querySelector('#disconnectButton'),
  clearLog: document.querySelector('#clearLogButton'),
  status: document.querySelector('#statusBadge'),
  pageAddress: document.querySelector('#pageAddress'),
  signalingAddress: document.querySelector('#signalingAddress'),
  video: document.querySelector('#remoteVideo'),
  placeholder: document.querySelector('#videoPlaceholder'),
  error: document.querySelector('#errorMessage'),
  eventLog: document.querySelector('#eventLog'),
  resolution: document.querySelector('#resolutionMetric'),
  fps: document.querySelector('#fpsMetric'),
  videoBitrate: document.querySelector('#videoBitrateMetric'),
  audioBitrate: document.querySelector('#audioBitrateMetric'),
  packetLoss: document.querySelector('#packetLossMetric'),
  rtt: document.querySelector('#rttMetric'),
};

let state = initialReceiverState(roomId);
let socket = null;
let peerConnection = null;
let remoteStream = null;
let pendingCandidates = [];
let statsTimer = null;
const previousBytes = new Map();

const websocketProtocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
const signalingUrl = `${websocketProtocol}//${location.host}/ws`;
elements.pageAddress.textContent = `${location.protocol}//${location.host}/`;
elements.signalingAddress.textContent = signalingUrl;

function appendLog(message) {
  const row = document.createElement('li');
  row.textContent = `${new Date().toLocaleTimeString()}  ${message}`;
  elements.eventLog.prepend(row);
  while (elements.eventLog.children.length > 40) elements.eventLog.lastElementChild.remove();
}

function dispatch(event) {
  state = reduceReceiverState(state, event);
  elements.status.dataset.phase = state.phase;
  elements.status.textContent = stateLabel(state.phase);
  elements.error.hidden = !state.error;
  elements.error.textContent = state.error;
  elements.start.disabled = ['signaling', 'waiting', 'connecting', 'streaming'].includes(state.phase);
  elements.disconnect.disabled = ['idle', 'error'].includes(state.phase);
}

function send(message) {
  if (socket?.readyState === WebSocket.OPEN) socket.send(JSON.stringify(message));
}

function fail(error) {
  const rawMessage = error instanceof Error ? error.message : String(error);
  const recovery = errorAction(rawMessage);
  const summary = rawMessage === 'UNSUPPORTED_BROWSER'
    ? '当前浏览器不支持 WebRTC 音视频接收。'
    : rawMessage === 'WEBSOCKET_FAILED'
      ? '无法连接信令服务。'
      : rawMessage;
  const message = recovery ? `${summary} ${recovery}` : summary;
  appendLog(`错误：${message}`);
  dispatch({ type: 'FAILED', message });
  closePeer();
}

function warn(error) {
  const message = error instanceof Error ? error.message : String(error);
  appendLog(`提示：${message}`);
  dispatch({ type: 'NON_FATAL_ERROR', message });
}

function createPeer() {
  if (peerConnection) return peerConnection;
  const pc = new RTCPeerConnection({ iceServers: [] });
  remoteStream = new MediaStream();
  elements.video.srcObject = remoteStream;
  pc.addTransceiver('video', { direction: 'recvonly' });
  pc.addTransceiver('audio', { direction: 'recvonly' });
  pc.ontrack = ({ track }) => {
    attachRemoteTrack(elements.video, remoteStream, track);
    elements.placeholder.hidden = true;
    elements.audio.disabled = false;
    elements.fullscreen.disabled = false;
    elements.video.play().catch(() => appendLog('浏览器阻止了自动播放，请点击“开启声音”'));
    appendLog(`收到 ${track.kind} 轨道`);
  };
  pc.onicecandidate = ({ candidate }) => {
    if (!candidate) return;
    send({
      type: 'ice-candidate',
      roomId,
      candidate: {
        sdpMid: candidate.sdpMid,
        sdpMLineIndex: candidate.sdpMLineIndex,
        candidate: candidate.candidate,
      },
    });
  };
  pc.oniceconnectionstatechange = () => {
    appendLog(`ICE：${pc.iceConnectionState}`);
    if (['connected', 'completed'].includes(pc.iceConnectionState)) {
      dispatch({ type: 'ICE_CONNECTED' });
      startStats();
    } else if (['failed'].includes(pc.iceConnectionState)) {
      fail('ICE 连接失败，请确认眼镜和 PC 在同一局域网');
    } else if (['disconnected', 'closed'].includes(pc.iceConnectionState)) {
      dispatch({ type: 'DISCONNECTED' });
    }
  };
  peerConnection = pc;
  return pc;
}

async function handleSignal(message) {
  switch (message.type) {
    case 'peer-ready':
      dispatch({ type: 'PEER_READY' });
      appendLog('眼镜已进入房间');
      createPeer();
      break;
    case 'offer': {
      const pc = createPeer();
      await pc.setRemoteDescription({ type: 'offer', sdp: message.sdp });
      for (const candidate of pendingCandidates.splice(0)) await pc.addIceCandidate(candidate);
      const answer = await pc.createAnswer();
      await pc.setLocalDescription(answer);
      send({ type: 'answer', roomId, sdp: answer.sdp });
      appendLog('已返回 WebRTC Answer');
      break;
    }
    case 'ice-candidate': {
      const candidate = new RTCIceCandidate(message.candidate);
      const pc = createPeer();
      if (pc.remoteDescription) await pc.addIceCandidate(candidate);
      else pendingCandidates.push(candidate);
      break;
    }
    case 'leave':
      appendLog('眼镜已断开');
      closePeer();
      dispatch({ type: 'PEER_LEFT' });
      break;
    case 'error':
      fail(message.message ?? '信令服务返回错误');
      break;
  }
}

function connect() {
  if (
    typeof WebSocket === 'undefined' ||
    typeof RTCPeerConnection === 'undefined' ||
    typeof MediaStream === 'undefined' ||
    typeof RTCIceCandidate === 'undefined'
  ) {
    fail('UNSUPPORTED_BROWSER');
    return;
  }
  disconnect(false);
  dispatch({ type: 'SIGNALING_CONNECTING' });
  appendLog(`连接 ${signalingUrl}`);
  socket = new WebSocket(signalingUrl);
  socket.onopen = () => {
    dispatch({ type: 'SIGNALING_OPEN' });
    send({ type: 'join', roomId, role: 'receiver' });
    appendLog('信令已连接，等待眼镜');
  };
  socket.onmessage = ({ data }) => {
    try {
      Promise.resolve(handleSignal(JSON.parse(data))).catch(fail);
    } catch (error) {
      fail(error);
    }
  };
  socket.onerror = () => fail('WEBSOCKET_FAILED');
  socket.onclose = () => {
    if (!['idle', 'error'].includes(state.phase)) {
      closePeer();
      dispatch({ type: 'STOPPED' });
      appendLog('信令连接已关闭');
    }
  };
}

function resetMetrics() {
  previousBytes.clear();
  elements.resolution.textContent = '—';
  elements.fps.textContent = '—';
  elements.videoBitrate.textContent = '—';
  elements.audioBitrate.textContent = '—';
  elements.packetLoss.textContent = '—';
  elements.rtt.textContent = '—';
}

function closePeer() {
  clearInterval(statsTimer);
  statsTimer = null;
  pendingCandidates = [];
  if (peerConnection) {
    peerConnection.ontrack = null;
    peerConnection.onicecandidate = null;
    peerConnection.oniceconnectionstatechange = null;
    peerConnection.close();
    peerConnection = null;
  }
  remoteStream?.getTracks().forEach((track) => track.stop());
  remoteStream = null;
  elements.video.srcObject = null;
  elements.video.muted = true;
  elements.audio.textContent = '开启声音';
  elements.audio.disabled = true;
  elements.fullscreen.disabled = true;
  elements.placeholder.hidden = false;
  resetMetrics();
}

function disconnect(updateState = true) {
  closePeer();
  if (socket) {
    socket.onclose = null;
    if (socket.readyState === WebSocket.OPEN) send({ type: 'leave', roomId });
    socket.close();
    socket = null;
  }
  if (updateState) {
    dispatch({ type: 'STOPPED' });
    appendLog('已停止接收');
  }
}

function bitrate(report) {
  const current = { bytes: Number(report.bytesReceived ?? 0), timestamp: Number(report.timestamp) };
  const previous = previousBytes.get(report.id);
  previousBytes.set(report.id, current);
  if (!previous || current.timestamp <= previous.timestamp) return 0;
  return Math.max(0, Math.round(((current.bytes - previous.bytes) * 8_000) / (current.timestamp - previous.timestamp)));
}

function formatRate(bitsPerSecond) {
  if (!bitsPerSecond) return '0 kbps';
  if (bitsPerSecond >= 1_000_000) return `${(bitsPerSecond / 1_000_000).toFixed(2)} Mbps`;
  return `${Math.round(bitsPerSecond / 1_000)} kbps`;
}

async function updateStats() {
  if (!peerConnection) return;
  const reports = await peerConnection.getStats();
  let lost = 0;
  let rttMs = null;
  reports.forEach((report) => {
    const kind = report.kind ?? report.mediaType;
    if (report.type === 'inbound-rtp' && !report.isRemote) {
      lost += Number(report.packetsLost ?? 0);
      if (kind === 'video') {
        elements.videoBitrate.textContent = formatRate(bitrate(report));
        elements.fps.textContent = `${Math.round(report.framesPerSecond ?? 0)} FPS`;
        if (report.frameWidth && report.frameHeight) {
          elements.resolution.textContent = `${report.frameWidth} × ${report.frameHeight}`;
        }
      } else if (kind === 'audio') {
        elements.audioBitrate.textContent = formatRate(bitrate(report));
      }
    }
    if (report.type === 'candidate-pair' && report.state === 'succeeded' && report.nominated) {
      rttMs = Number(report.currentRoundTripTime ?? 0) * 1_000;
    }
  });
  elements.packetLoss.textContent = String(lost);
  elements.rtt.textContent = rttMs === null ? '—' : `${Math.round(rttMs)} ms`;
}

function startStats() {
  clearInterval(statsTimer);
  updateStats().catch(fail);
  statsTimer = setInterval(() => updateStats().catch(fail), 1_000);
}

elements.start.addEventListener('click', connect);
elements.disconnect.addEventListener('click', () => disconnect(true));
elements.audio.addEventListener('click', () => {
  elements.video.muted = !elements.video.muted;
  elements.audio.textContent = elements.video.muted ? '开启声音' : '静音';
  elements.video.play().catch((error) => {
    elements.video.muted = true;
    elements.audio.textContent = '开启声音';
    warn(error);
  });
});
elements.fullscreen.addEventListener('click', () => elements.video.requestFullscreen?.().catch(warn));
elements.clearLog.addEventListener('click', () => elements.eventLog.replaceChildren());
window.addEventListener('beforeunload', () => disconnect(false));

dispatch({ type: 'STOPPED' });
