const path = require('path');
const http = require('http');
const express = require('express');
const { Server } = require('socket.io');

const PORT = process.env.PORT ? Number(process.env.PORT) : 3000;
const HOST = process.env.HOST || '0.0.0.0';

const app = express();
const server = http.createServer(app);
const io = new Server(server, {
  cors: {
    origin: '*',
    methods: ['GET', 'POST'],
  },
});

const roomStatus = new Map();

function normalizePin(raw) {
  const digits = String(raw == null ? '' : raw).replace(/\D/g, '').slice(0, 4);
  return digits.length === 4 ? digits : null;
}

function roomName(pin) {
  return `match:${pin}`;
}

app.get('/', (_req, res) => {
  res.sendFile(path.join(__dirname, 'sender.html'));
});

app.get('/receiver.html', (_req, res) => {
  res.sendFile(path.join(__dirname, 'receiver.html'));
});

app.use(express.static(__dirname));

io.on('connection', (socket) => {
  socket.data.matchId = null;

  socket.on('join', (data = {}) => {
    const pin = normalizePin(data.matchId);
    if (!pin) {
      socket.emit('join_error', { message: '4 digit code chahiye' });
      return;
    }

    if (socket.data.matchId && socket.data.matchId !== pin) {
      socket.leave(roomName(socket.data.matchId));
    }

    socket.data.matchId = pin;
    socket.join(roomName(pin));
    socket.emit('joined', { matchId: pin });

    const enabled = roomStatus.get(pin);
    if (typeof enabled === 'boolean') {
      socket.emit('sender_status', { enabled, time: Date.now() });
    }
  });

  socket.on('broadcast', ({ type, payload } = {}) => {
    const pin = socket.data.matchId;
    if (!pin) return;
    socket.to(roomName(pin)).emit('event', { type, payload, time: Date.now() });
  });

  socket.on('sender_status', ({ enabled } = {}) => {
    const pin = socket.data.matchId;
    if (!pin) return;
    const on = !!enabled;
    roomStatus.set(pin, on);
    socket.to(roomName(pin)).emit('sender_status', { enabled: on, time: Date.now() });
  });

  socket.on('disconnect', () => {
    socket.data.matchId = null;
  });
});

server.listen(PORT, HOST, () => {
  // eslint-disable-next-line no-console
  console.log(`Cricket DRS server running on http://${HOST}:${PORT}`);
});
