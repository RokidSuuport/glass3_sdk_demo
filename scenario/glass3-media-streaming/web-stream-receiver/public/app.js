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
// The socket can survive several peers (leave/rejoin). Invalidate both socket
// identity and peer epoch, including promises which cannot actually be cancelled.
let peerEpoch = 0;
let media = null;
const MEDIA_STALE_MS = 3_000;
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
  elements.start.disabled = !['idle', 'error'].includes(state.phase);
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
  disconnect(false);
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
  const stream = remoteStream;
  const evidence = {
    videoTrack: false, audioTrack: false, lastVideoAt: null, lastAudioAt: null,
    counters: new Map(), frameCallback: null, statsPending: false,
  };
  media = evidence;
  elements.video.srcObject = remoteStream;
  pc.addTransceiver('video', { direction: 'recvonly' });
  pc.addTransceiver('audio', { direction: 'recvonly' });
  pc.ontrack = ({ track }) => {
    if (peerConnection !== pc) return;
    attachRemoteTrack(elements.video, stream, track);
    evidence[`${track.kind}Track`] = true;
    renderPlaybackButton(evidence);
    // ontrack describes a negotiated track, not a decoded frame. Only a browser
    // presentation callback (or decoded-frame stats fallback) reveals real video.
    if (track.kind === 'video' && evidence.frameCallback === null && elements.video.requestVideoFrameCallback) {
      const onFrame = () => {
        if (peerConnection !== pc) return;
        evidence.lastVideoAt = performance.now();
        renderMedia(pc, evidence);
        evidence.frameCallback = elements.video.requestVideoFrameCallback(onFrame);
      };
      evidence.frameCallback = elements.video.requestVideoFrameCallback(onFrame);
    }
    track.onended = () => {
      if (peerConnection !== pc) return;
      evidence[`${track.kind}Track`] = false;
      if (track.kind === 'video') evidence.lastVideoAt = null;
      else evidence.lastAudioAt = null;
      renderPlaybackButton(evidence);
      renderMedia(pc, evidence);
    };
    elements.video.play().catch(() => {
      if (peerConnection === pc) {
        appendLog(`浏览器阻止了自动播放，请点击“${evidence.audioTrack ? '开启声音' : '播放视频'}”`);
      }
    });
    appendLog(`收到 ${track.kind} 轨道`);
  };
  pc.onicecandidate = ({ candidate }) => {
    if (peerConnection !== pc || !candidate) return;
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
    if (peerConnection !== pc) return;
    appendLog(`ICE：${pc.iceConnectionState}`);
    if (['connected', 'completed'].includes(pc.iceConnectionState)) {
      dispatch({ type: 'ICE_CONNECTED' });
      renderMedia(pc, evidence);
      startStats(pc, evidence);
    } else if (['failed'].includes(pc.iceConnectionState)) {
      fail('ICE 连接失败，请确认眼镜和 PC 在同一局域网');
    } else if (['disconnected', 'closed'].includes(pc.iceConnectionState)) {
      evidence.lastVideoAt = null;
      evidence.lastAudioAt = null;
      dispatch({ type: 'DISCONNECTED' });
      elements.placeholder.hidden = false;
      setPlaceholder('网络连接中断，等待恢复', '请检查眼镜和 PC 的网络连接。');
      elements.fullscreen.disabled = true;
    }
  };
  peerConnection = pc;
  return pc;
}

async function handleSignal(message, expectedSocket, expectedEpoch) {
  const isCurrent = () => socket === expectedSocket && peerEpoch === expectedEpoch;
  if (!isCurrent()) return;
  switch (message.type) {
    case 'peer-ready':
      dispatch({ type: 'PEER_READY' });
      appendLog('眼镜已进入房间');
      createPeer();
      setPlaceholder('正在协商音视频', '连接建立后仍需等待真实媒体数据。');
      break;
    case 'offer': {
      const pc = createPeer();
      await pc.setRemoteDescription({ type: 'offer', sdp: message.sdp });
      if (!isCurrent()) return;
      for (const candidate of pendingCandidates.splice(0)) {
        await pc.addIceCandidate(candidate);
        if (!isCurrent()) return;
      }
      const answer = await pc.createAnswer();
      if (!isCurrent()) return;
      await pc.setLocalDescription(answer);
      if (!isCurrent()) return;
      send({ type: 'answer', roomId, sdp: answer.sdp });
      appendLog('已返回 WebRTC Answer');
      break;
    }
    case 'ice-candidate': {
      const candidate = new RTCIceCandidate(message.candidate);
      const pc = createPeer();
      if (pc.remoteDescription) {
        await pc.addIceCandidate(candidate);
        if (!isCurrent()) return;
      }
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
  const ws = new WebSocket(signalingUrl);
  socket = ws;
  ws.onopen = () => {
    if (socket !== ws) return;
    dispatch({ type: 'SIGNALING_OPEN' });
    send({ type: 'join', roomId, role: 'receiver' });
    appendLog('信令已连接，等待眼镜');
  };
  ws.onmessage = ({ data }) => {
    if (socket !== ws) return;
    const expectedEpoch = peerEpoch;
    const failIfCurrent = (error) => {
      if (socket === ws && peerEpoch === expectedEpoch) fail(error);
    };
    try {
      handleSignal(JSON.parse(data), ws, expectedEpoch).catch(failIfCurrent);
    } catch (error) {
      failIfCurrent(error);
    }
  };
  ws.onerror = () => { if (socket === ws) fail('WEBSOCKET_FAILED'); };
  ws.onclose = () => {
    if (socket !== ws) return;
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
  peerEpoch += 1;
  clearInterval(statsTimer);
  statsTimer = null;
  pendingCandidates = [];
  if (media?.frameCallback !== null && media?.frameCallback !== undefined) {
    elements.video.cancelVideoFrameCallback?.(media.frameCallback);
  }
  media = null;
  if (peerConnection) {
    peerConnection.ontrack = null;
    peerConnection.onicecandidate = null;
    peerConnection.oniceconnectionstatechange = null;
    peerConnection.close();
    peerConnection = null;
  }
  remoteStream?.getTracks().forEach((track) => { track.onended = null; track.stop(); });
  remoteStream = null;
  elements.video.srcObject = null;
  elements.video.muted = true;
  elements.audio.textContent = '开启声音';
  elements.audio.disabled = true;
  elements.fullscreen.disabled = true;
  elements.placeholder.hidden = false;
  setPlaceholder('等待眼镜连接', '先点击“开始接收”，再在眼镜端填写本机局域网地址。');
  resetMetrics();
}

function disconnect(updateState = true) {
  closePeer();
  if (socket) {
    const ws = socket;
    if (ws.readyState === WebSocket.OPEN) send({ type: 'leave', roomId });
    socket = null;
    ws.onopen = null;
    ws.onmessage = null;
    ws.onerror = null;
    ws.onclose = null;
    ws.close();
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

function setPlaceholder(title, detail) {
  elements.placeholder.querySelector('strong').textContent = title;
  elements.placeholder.querySelector('span').textContent = detail;
}

function renderPlaybackButton(evidence) {
  // A track can require a user gesture before it ever produces a displayed
  // frame. Do not gate playback recovery on RTP statistics or decoded video.
  elements.audio.disabled = !evidence.videoTrack && !evidence.audioTrack;
  elements.audio.textContent = evidence.audioTrack
    ? (elements.video.muted ? '开启声音' : '静音') : '播放视频';
}

function renderMedia(pc, evidence) {
  if (peerConnection !== pc || !['connected', 'completed'].includes(pc.iceConnectionState)) return;
  const now = performance.now();
  const fresh = (at) => at !== null && now - at < MEDIA_STALE_MS;
  const video = evidence.videoTrack && fresh(evidence.lastVideoAt);
  const audio = evidence.audioTrack && fresh(evidence.lastAudioAt);
  dispatch({ type: 'MEDIA_STATUS', video, audio });
  elements.placeholder.hidden = video;
  elements.fullscreen.disabled = !video;
  renderPlaybackButton(evidence);
  if (audio && !video) {
    setPlaceholder('正在接收音频', evidence.videoTrack
      ? '视频尚未出帧或暂时中断，等待画面恢复。'
      : '眼镜未发送视频轨道；点击“开启声音”收听。');
  } else if (!video) {
    setPlaceholder(evidence.lastVideoAt === null ? '连接已建立，等待媒体数据' : '视频暂时中断，等待恢复',
      '尚未收到可显示的画面或连续音频，请检查眼镜采集状态。');
  }
}

async function updateStats(pc, evidence) {
  if (peerConnection !== pc || evidence.statsPending) return;
  evidence.statsPending = true;
  let reports;
  try {
    reports = await pc.getStats();
  } catch (error) {
    if (peerConnection === pc) appendLog(`统计暂不可用：${error.message ?? error}`);
    return;
  } finally {
    evidence.statsPending = false;
  }
  if (peerConnection !== pc) return;
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
        // Fallback for browsers without requestVideoFrameCallback: both decoded
        // frames and a media element with current video data are required.
        const decoded = Number(report.framesDecoded ?? 0);
        const previous = evidence.counters.get(report.id) ?? 0;
        evidence.counters.set(report.id, decoded);
        if (!elements.video.requestVideoFrameCallback && decoded > previous &&
            elements.video.readyState >= 2 && elements.video.videoWidth > 0) {
          evidence.lastVideoAt = performance.now();
        }
      } else if (kind === 'audio') {
        elements.audioBitrate.textContent = formatRate(bitrate(report));
        const bytes = Number(report.bytesReceived ?? 0);
        const previous = evidence.counters.get(report.id) ?? 0;
        evidence.counters.set(report.id, bytes);
        if (bytes > previous) evidence.lastAudioAt = performance.now();
      }
    }
    if (report.type === 'candidate-pair' && report.state === 'succeeded' && report.nominated) {
      rttMs = Number(report.currentRoundTripTime ?? 0) * 1_000;
    }
  });
  elements.packetLoss.textContent = String(lost);
  elements.rtt.textContent = rttMs === null ? '—' : `${Math.round(rttMs)} ms`;
  renderMedia(pc, evidence);
}

function startStats(pc, evidence) {
  clearInterval(statsTimer);
  const poll = () => {
    renderMedia(pc, evidence);
    // A statistics promise belongs only to its captured peer. Statistics errors
    // are diagnostic, not grounds to terminate an otherwise working media path.
    updateStats(pc, evidence);
  };
  poll();
  statsTimer = setInterval(poll, 1_000);
}

elements.start.addEventListener('click', connect);
elements.disconnect.addEventListener('click', () => disconnect(true));
elements.audio.addEventListener('click', () => {
  const pc = peerConnection;
  const evidence = media;
  if (!pc || !evidence) return;
  if (evidence.audioTrack) elements.video.muted = !elements.video.muted;
  renderPlaybackButton(evidence);
  elements.video.play().catch((error) => {
    if (peerConnection !== pc) return;
    elements.video.muted = true;
    renderPlaybackButton(evidence);
    warn(error);
  });
});
elements.fullscreen.addEventListener('click', () => {
  const pc = peerConnection;
  elements.video.requestFullscreen?.().catch((error) => { if (peerConnection === pc) warn(error); });
});
elements.clearLog.addEventListener('click', () => elements.eventLog.replaceChildren());
window.addEventListener('beforeunload', () => disconnect(false));

dispatch({ type: 'STOPPED' });
