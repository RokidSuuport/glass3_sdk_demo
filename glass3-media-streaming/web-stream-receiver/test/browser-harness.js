import { readFile } from 'node:fs/promises';
import vm from 'node:vm';
import * as receiverState from '../public/receiver-state.js';
import { attachRemoteTrack } from '../public/remote-media.js';

export function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

export async function flush() {
  for (let index = 0; index < 12; index += 1) await Promise.resolve();
}

// Run the actual browser entry point. Only browser-owned DOM/RTC/network/timing
// boundaries are doubled; state transitions, signaling and rendering stay real.
export async function browserHarness() {
  const elements = new Map();
  const intervals = new Map();
  const frameCallbacks = new Map();
  let nextId = 0;
  let now = 0;
  function element() {
    const listeners = new Map();
    return {
      textContent: '', hidden: false, disabled: false, muted: true, dataset: {},
      children: [], srcObject: null, videoWidth: 0, videoHeight: 0, readyState: 0,
      addEventListener(type, listener) { listeners.set(type, listener); },
      emit(type) { listeners.get(type)?.(); this[`on${type}`]?.(); },
      prepend(row) { this.children.unshift(row); },
      replaceChildren() { this.children = []; },
      querySelector(selector) {
        if (!this.parts) this.parts = new Map();
        if (!this.parts.has(selector)) this.parts.set(selector, element());
        return this.parts.get(selector);
      },
      play() { return Promise.resolve(); },
      requestVideoFrameCallback(callback) { const id = ++nextId; frameCallbacks.set(id, callback); return id; },
      cancelVideoFrameCallback(id) { frameCallbacks.delete(id); },
    };
  }
  const sockets = [];
  class BrowserSocket {
    static OPEN = 1;
    constructor() { this.readyState = 0; this.sent = []; sockets.push(this); }
    open() { this.readyState = 1; this.onopen?.(); }
    message(message) { this.onmessage?.({ data: JSON.stringify(message) }); }
    send(data) { this.sent.push(JSON.parse(data)); }
    close() { this.readyState = 3; this.onclose?.(); }
  }
  const peers = [];
  class BrowserPeer {
    constructor() {
      this.closed = false;
      this.remoteDescription = null;
      this.stats = new Map();
      this.iceConnectionState = 'new';
      peers.push(this);
    }
    addTransceiver() {}
    async setRemoteDescription(description) { this.remoteDescription = description; }
    async addIceCandidate() {}
    async createAnswer() { return { type: 'answer', sdp: 'current-answer' }; }
    async setLocalDescription() {}
    getStats() { return Promise.resolve(this.stats); }
    close() { this.closed = true; }
    connect() { this.iceConnectionState = 'connected'; this.oniceconnectionstatechange?.(); }
    track(kind) {
      const track = { kind, id: `${kind}-${peers.indexOf(this)}`, readyState: 'live', stop() { this.readyState = 'ended'; } };
      this.ontrack?.({ track });
      return track;
    }
  }
  class BrowserStream {
    constructor() { this.tracks = []; }
    getTracks() { return this.tracks; }
    addTrack(track) { this.tracks.push(track); }
  }
  const context = vm.createContext({
    ...receiverState, attachRemoteTrack, console, Error, Map, Date,
    document: {
      querySelector(selector) {
        if (!elements.has(selector)) elements.set(selector, element());
        return elements.get(selector);
      },
      createElement: element,
    },
    location: { protocol: 'http:', host: 'localhost:8080' },
    window: { addEventListener() {} },
    performance: { now: () => now },
    WebSocket: BrowserSocket, RTCPeerConnection: BrowserPeer, MediaStream: BrowserStream,
    RTCIceCandidate: class { constructor(candidate) { Object.assign(this, candidate); } },
    setInterval(callback) { const id = ++nextId; intervals.set(id, callback); return id; },
    clearInterval(id) { intervals.delete(id); },
  });
  const source = await readFile(new URL('../public/app.js', import.meta.url), 'utf8');
  vm.runInContext(source.replace(/^import\s[\s\S]*?from\s+'[^']+';\s*/gm, ''), context);
  const get = (id) => elements.get(`#${id}`);
  return {
    get, sockets, peers,
    start() {
      get('startButton').emit('click');
      const socket = sockets.at(-1);
      socket.open();
      socket.message({ type: 'peer-ready', roomId: 'default' });
      return { socket, pc: peers.at(-1) };
    },
    stop() { get('disconnectButton').emit('click'); },
    async tick(milliseconds = 1_000) {
      now += milliseconds;
      [...intervals.values()].forEach((callback) => callback());
      await flush();
    },
    frame() {
      get('remoteVideo').videoWidth = 1280;
      get('remoteVideo').videoHeight = 720;
      get('remoteVideo').readyState = 2;
      const callbacks = [...frameCallbacks.values()];
      frameCallbacks.clear();
      callbacks.forEach((callback) => callback(now, { presentedFrames: 1, width: 1280, height: 720 }));
    },
  };
}
