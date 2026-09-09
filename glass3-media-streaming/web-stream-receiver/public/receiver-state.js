export function initialReceiverState(roomId = 'default') {
  return { roomId, phase: 'idle', error: '' };
}

const STATE_LABELS = Object.freeze({
  idle: '未启动',
  signaling: '正在连接信令服务',
  waiting: '等待眼镜端连接',
  'waiting-sender': '等待眼镜端连接',
  connecting: '正在协商音视频',
  streaming: '正在接收音视频',
  disconnected: '连接已断开',
  error: '连接异常',
});

/** 返回客户可以直接理解的连接状态，不把内部状态名称显示到页面。 */
export function stateLabel(phase) {
  return STATE_LABELS[phase] ?? '状态未知';
}

/**
 * 将常见浏览器和一对一房间错误转换为可直接执行的处理建议。
 * 未知错误保留服务端原文，避免隐藏真正的集成问题。
 */
export function errorAction(error) {
  const message = String(error ?? '');
  if (message === 'UNSUPPORTED_BROWSER') {
    return '请使用最新版 Chrome 或 Edge 打开当前页面。';
  }
  if (message === 'WEBSOCKET_FAILED') {
    return '请确认本页面地址可访问、接收服务仍在运行，并检查眼镜与 PC 的网络。';
  }
  if (message.includes('receiver already joined')) {
    return '同一房间已有其他接收页面，请关闭后再点击“开始接收”。';
  }
  if (message.includes('sender already joined')) {
    return '同一房间已有一台眼镜，请先停止原眼镜端传输。';
  }
  if (message.includes('ICE')) {
    return '请确认眼镜与 PC 在同一可互访局域网，且防火墙未阻止点对点连接。';
  }
  return '';
}

export function reduceReceiverState(state, event) {
  switch (event.type) {
    case 'SIGNALING_CONNECTING':
      return { ...state, phase: 'signaling', error: '' };
    case 'SIGNALING_OPEN':
      return { ...state, phase: 'waiting', error: '' };
    case 'PEER_READY':
      return { ...state, phase: 'connecting', error: '' };
    case 'ICE_CONNECTED':
      return { ...state, phase: 'streaming', error: '' };
    case 'PEER_LEFT':
      return { ...state, phase: 'waiting', error: '' };
    case 'DISCONNECTED':
      return { ...state, phase: 'disconnected', error: '' };
    case 'STOPPED':
      return { ...state, phase: 'idle', error: '' };
    case 'FAILED':
      return { ...state, phase: 'error', error: event.message };
    case 'NON_FATAL_ERROR':
      return { ...state, error: event.message };
    default:
      return state;
  }
}
