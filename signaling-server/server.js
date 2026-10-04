// HomeWatch signaling server.
//
// This replaces the original ChatGPT-generated server. The root cause of
// "one phone connects, the other just says Connecting forever" was almost
// certainly one or both of:
//   1. No TURN relay server was configured - only STUN. STUN lets two phones
//      discover their public IP/port, but on many mobile networks (carrier-
//      grade NAT, which is extremely common in India on phones like these)
//      that's not enough to actually establish a direct connection. Without
//      a TURN relay to fall back to, the connection just hangs forever with
//      no error - exactly the symptom described.
//   2. No clear "who makes the offer" rule, so both phones could end up
//      waiting on each other, or racing, depending on timing.
//
// This server fixes both: it tells each phone explicitly whether it's the
// offer-maker, and it hands out short-lived, genuinely free TURN credentials
// from Cloudflare's Realtime TURN service (1TB/month free) so a relay path
// is always available as a fallback when a direct connection can't form.

const http = require('http');
const https = require('https');
const WebSocket = require('ws');

const PORT = process.env.PORT || 10000;

// Optional: set these in Render's environment variables to enable free TURN
// relay via Cloudflare. Without them, the server still works using public
// STUN servers only - which is exactly the setup that was failing before.
const CF_TURN_APP_ID = process.env.CF_TURN_APP_ID || '';
const CF_TURN_TOKEN = process.env.CF_TURN_TOKEN || '';

const ROOM_MAX_PEERS = 2;

function fallbackIceServers() {
  return [
    { urls: ['stun:stun.l.google.com:19302'] },
    { urls: ['stun:stun1.l.google.com:19302'] },
  ];
}

function fetchTurnCredentials() {
  return new Promise((resolve) => {
    if (!CF_TURN_APP_ID || !CF_TURN_TOKEN) {
      resolve(fallbackIceServers());
      return;
    }
    const body = JSON.stringify({ ttl: 86400 });
    const options = {
      hostname: 'rtc.live.cloudflare.com',
      path: `/v1/turn/keys/${CF_TURN_APP_ID}/credentials/generate-ice-servers`,
      method: 'POST',
      headers: {
        Authorization: `Bearer ${CF_TURN_TOKEN}`,
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(body),
      },
      timeout: 8000,
    };
    const req = https.request(options, (res) => {
      let data = '';
      res.on('data', (c) => (data += c));
      res.on('end', () => {
        try {
          const parsed = JSON.parse(data);
          let servers = parsed.iceServers;
          if (servers && !Array.isArray(servers)) servers = [servers];
          if (!servers || servers.length === 0) throw new Error('empty response');
          resolve(servers.concat(fallbackIceServers()));
        } catch (e) {
          console.error('TURN credential parse failed, falling back to STUN only:', e.message);
          resolve(fallbackIceServers());
        }
      });
    });
    req.on('timeout', () => req.destroy());
    req.on('error', (e) => {
      console.error('TURN credential request failed, falling back to STUN only:', e.message);
      resolve(fallbackIceServers());
    });
    req.write(body);
    req.end();
  });
}

const server = http.createServer((req, res) => {
  if (req.url === '/' || req.url === '/health') {
    res.writeHead(200, { 'Content-Type': 'text/plain' });
    res.end('HomeWatch signaling server is running');
    return;
  }
  if (req.url === '/ice-servers') {
    fetchTurnCredentials().then((iceServers) => {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ iceServers }));
    });
    return;
  }
  res.writeHead(404, { 'Content-Type': 'text/plain' });
  res.end('Not found');
});

const wss = new WebSocket.Server({ server });

/** roomCode -> array of connected client sockets (max 2) */
const rooms = new Map();

function genId() {
  return Math.random().toString(36).slice(2, 10);
}

function send(ws, obj) {
  if (ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify(obj));
  }
}

function otherPeer(room, ws) {
  const peers = rooms.get(room) || [];
  return peers.find((p) => p !== ws);
}

function removeFromRoom(ws) {
  if (!ws.room) return;
  const peers = rooms.get(ws.room);
  if (!peers) return;
  const idx = peers.indexOf(ws);
  if (idx !== -1) peers.splice(idx, 1);
  const remaining = peers[0];
  if (remaining) send(remaining, { type: 'peer-left' });
  if (peers.length === 0) rooms.delete(ws.room);
  ws.room = null;
}

const RELAYED_TYPES = new Set(['offer', 'answer', 'candidate', 'chat', 'media-state', 'bye', 'motion', 'siren']);

wss.on('connection', (ws) => {
  ws.isAlive = true;
  ws.id = genId();
  ws.room = null;
  ws.name = 'Guest';

  ws.on('pong', () => {
    ws.isAlive = true;
  });

  ws.on('message', (raw) => {
    let msg;
    try {
      msg = JSON.parse(raw.toString());
    } catch (e) {
      return;
    }
    if (!msg || typeof msg.type !== 'string') return;

    if (msg.type === 'join') {
      const room = String(msg.room || '').trim().toUpperCase();
      if (!room) {
        send(ws, { type: 'error', message: 'A room code is required' });
        return;
      }
      if (ws.room) removeFromRoom(ws);

      let peers = rooms.get(room);
      if (!peers) {
        peers = [];
        rooms.set(room, peers);
      }
      if (peers.length >= ROOM_MAX_PEERS) {
        send(ws, { type: 'room-full' });
        return;
      }

      ws.room = room;
      ws.name = String(msg.name || 'Guest').slice(0, 40);
      const isInitiator = peers.length === 0;
      peers.push(ws);

      send(ws, { type: 'joined', id: ws.id, initiator: isInitiator, room });

      if (peers.length === ROOM_MAX_PEERS) {
        const other = otherPeer(room, ws);
        send(ws, { type: 'peer-joined', id: other.id, name: other.name });
        send(other, { type: 'peer-joined', id: ws.id, name: ws.name });
      }
      return;
    }

    if (msg.type === 'leave') {
      removeFromRoom(ws);
      return;
    }

    if (RELAYED_TYPES.has(msg.type)) {
      const other = ws.room ? otherPeer(ws.room, ws) : null;
      if (other) {
        msg.from = ws.id;
        send(other, msg);
      }
      return;
    }
  });

  ws.on('close', () => removeFromRoom(ws));
  ws.on('error', () => removeFromRoom(ws));
});

// Detect and drop dead connections (phone lost signal, app killed, etc.)
// so the other side gets a timely "peer-left" instead of hanging forever.
const keepaliveInterval = setInterval(() => {
  wss.clients.forEach((ws) => {
    if (ws.isAlive === false) {
      removeFromRoom(ws);
      return ws.terminate();
    }
    ws.isAlive = false;
    ws.ping();
  });
}, 25000);

wss.on('close', () => clearInterval(keepaliveInterval));

server.listen(PORT, () => {
  console.log('HomeWatch signaling server listening on port', PORT);
  console.log('TURN relay:', CF_TURN_APP_ID ? 'Cloudflare configured' : 'NOT configured - STUN only (see README)');
});
