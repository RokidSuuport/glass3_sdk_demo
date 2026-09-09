const TYPES = new Set([
  'join',
  'peer-ready',
  'offer',
  'answer',
  'ice-candidate',
  'leave',
  'error',
]);
const ROLES = new Set(['sender', 'receiver']);
const ROOM_ID = /^[A-Za-z0-9_-]{1,32}$/;

export const MAX_MESSAGE_BYTES = 64 * 1024;

export function parseEnvelope(raw) {
  if (typeof raw !== 'string' || Buffer.byteLength(raw, 'utf8') > MAX_MESSAGE_BYTES) {
    throw new Error('invalid message size');
  }

  const message = JSON.parse(raw);
  if (!message || typeof message !== 'object' || Array.isArray(message) || !TYPES.has(message.type)) {
    throw new Error('invalid message type');
  }
  if (message.roomId !== undefined && !ROOM_ID.test(message.roomId)) {
    throw new Error('invalid roomId');
  }
  if (message.role !== undefined && !ROLES.has(message.role)) {
    throw new Error('invalid role');
  }
  return message;
}

export function encodeEnvelope(message) {
  return JSON.stringify(message);
}
