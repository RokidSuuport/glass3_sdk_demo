export class RoomRegistry {
  constructor() {
    this.rooms = new Map();
    this.memberships = new Map();
  }

  join(roomId, role, socket) {
    const current = this.memberships.get(socket);
    if (current && (current.roomId !== roomId || current.role !== role)) {
      throw new Error('socket already joined');
    }

    const room = this.rooms.get(roomId) ?? { sender: null, receiver: null };
    if (room[role] && room[role] !== socket) {
      throw new Error(`${role} already joined`);
    }

    room[role] = socket;
    this.rooms.set(roomId, room);
    this.memberships.set(socket, { roomId, role });
    return { peer: role === 'sender' ? room.receiver : room.sender };
  }

  peerOf(socket) {
    const member = this.memberships.get(socket);
    if (!member) return null;
    const room = this.rooms.get(member.roomId);
    if (!room) return null;
    return member.role === 'sender' ? room.receiver : room.sender;
  }

  leave(socket) {
    const member = this.memberships.get(socket);
    if (!member) return { peer: null };

    const peer = this.peerOf(socket);
    const room = this.rooms.get(member.roomId);
    if (room?.[member.role] === socket) {
      room[member.role] = null;
    }
    this.memberships.delete(socket);
    if (room && !room.sender && !room.receiver) {
      this.rooms.delete(member.roomId);
    }
    return { peer };
  }
}
