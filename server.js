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

app.get('/', (_req, res) => {
  res.sendFile(path.join(__dirname, 'sender.html'));
});

app.get('/receiver.html', (_req, res) => {
  res.sendFile(path.join(__dirname, 'receiver.html'));
});

app.use(express.static(__dirname));

io.on('connection', (socket) => {
  socket.on('broadcast', ({ type, payload }) => {
    // Sender broadcasts event to all other connected clients (receivers)
    socket.broadcast.emit('event', { type, payload, time: Date.now() });
  });

  socket.on('disconnect', () => {
    // Cleanup on disconnect
  });
});

server.listen(PORT, HOST, () => {
  // eslint-disable-next-line no-console
  console.log(`Cricket DRS server running on http://${HOST}:${PORT}`);
});
