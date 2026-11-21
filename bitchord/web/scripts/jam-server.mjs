import crypto from 'node:crypto';
import http from 'node:http';

const PORT = Number(process.env.PORT || 8787);
const HOST = process.env.HOST || '0.0.0.0';
const PUBLIC_HOST = process.env.AUREX_PUBLIC_HOST || '192.168.1.13';
const WEB_PORT = Number(process.env.AUREX_WEB_PORT || 5173);

const CODE_ALPHABET = '0123456789ABCDEFGHJKMNPQRSTUVWXYZ';
const CODE_LENGTH = 6;
const PLAY_LEAD_MS = 350;
const HEARTBEAT_INTERVAL_MS = 5000;
const DISCONNECT_GRACE_MS = 60_000;
const EMPTY_PARTY_TTL_MS = 10 * 60_000;
const PARTY_MAX_AGE_MS = 24 * 60 * 60_000;
const MAX_UPCOMING_QUEUE = 25;
const WS_MAGIC_GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11';

const wallAnchorMs = Date.now();
const hrAnchorNs = process.hrtime.bigint();
function nowMs() {
  const elapsedMs = Number((process.hrtime.bigint() - hrAnchorNs) / 1_000_000n);
  return wallAnchorMs + elapsedMs;
}

function normaliseCode(raw) {
  let out = '';
  for (const ch of String(raw || '').trim().toUpperCase()) {
    if (!/[A-Z0-9]/.test(ch)) continue;
    if (ch === 'I' || ch === 'L') out += '1';
    else if (ch === 'O') out += '0';
    else out += ch;
  }
  return out;
}

function generateCode(existing) {
  for (let attempt = 0; attempt < 16; attempt++) {
    let code = '';
    for (let i = 0; i < CODE_LENGTH; i++) {
      code += CODE_ALPHABET[crypto.randomInt(CODE_ALPHABET.length)];
    }
    if (!existing.has(code)) return code;
  }
  throw new Error('code_exhausted');
}

function normalizeTrack(raw) {
  if (!raw || typeof raw !== 'object') return null;
  const videoId = typeof raw.videoId === 'string' ? raw.videoId.slice(0, 128) : '';
  if (!videoId) return null;
  const title = typeof raw.title === 'string' ? raw.title.slice(0, 300) : '';
  const artist = typeof raw.artist === 'string' ? raw.artist.slice(0, 300) : '';
  const thumbnailUrl =
    typeof raw.thumbnailUrl === 'string' && raw.thumbnailUrl.length > 0
      ? raw.thumbnailUrl.slice(0, 1000)
      : null;
  const durationMs =
    typeof raw.durationMs === 'number' && Number.isFinite(raw.durationMs) && raw.durationMs > 0
      ? Math.round(raw.durationMs)
      : null;
  const fromAutoplay = Boolean(raw.fromAutoplay);
  return { videoId, title, artist, thumbnailUrl, durationMs, fromAutoplay };
}

function nearestIndexOf(queue, videoId, around) {
  if (around + 1 >= 0 && around + 1 < queue.length && queue[around + 1]?.videoId === videoId) {
    return around + 1;
  }
  if (around - 1 >= 0 && around - 1 < queue.length && queue[around - 1]?.videoId === videoId) {
    return around - 1;
  }
  let best = -1;
  let bestDistance = 0;
  for (let i = 0; i < queue.length; i++) {
    if (queue[i].videoId !== videoId) continue;
    let distance = i - around;
    if (distance < 0) distance = -distance * 2 + 1;
    if (best === -1 || distance < bestDistance) {
      best = i;
      bestDistance = distance;
    }
  }
  return best;
}

function positionAt(pb, serverMs) {
  if (!pb.isPlaying) return pb.positionMs;
  const elapsed = Math.max(0, serverMs - pb.anchorMs);
  const pos = pb.positionMs + elapsed;
  if (pb.track && pb.track.durationMs && pb.track.durationMs > 0) {
    return Math.min(pos, pb.track.durationMs);
  }
  return pos;
}

function playbackToWire(pb, serverMs) {
  return {
    seq: pb.seq,
    track: pb.track,
    queueSeq: pb.queueSeq,
    queueLength: pb.queue.length,
    queueIndex: pb.queueIndex,
    isPlaying: pb.isPlaying,
    positionMs: pb.positionMs,
    anchorMs: pb.anchorMs,
    effectivePositionMs: positionAt(pb, serverMs),
    updatedBy: pb.updatedBy,
    startedBy: pb.startedBy,
    startedByName: pb.startedByName,
    autoplayEnabled: pb.autoplayEnabled,
    updatedAtMs: pb.updatedAtMs,
  };
}

function queueToWire(pb) {
  return {
    seq: pb.queueSeq,
    index: pb.queueIndex,
    items: pb.queue,
  };
}

function memberToWire(m) {
  return {
    memberId: m.memberId,
    userId: m.userId,
    displayName: m.displayName,
    avatarUrl: m.avatarUrl,
    isHost: m.isHost,
    connected: m.connected,
    joinedAtMs: m.joinedAtMs,
    lastSeenMs: m.lastSeenMs,
  };
}

function partyToWire(party, serverMs = nowMs()) {
  const ordered = [...party.members.values()].sort((a, b) => a.joinedAtMs - b.joinedAtMs);
  return {
    code: party.code,
    createdAtMs: party.createdAtMs,
    maxMembers: party.maxMembers,
    hostOnlyControl: party.hostOnlyControl,
    members: ordered.map(memberToWire),
    playback: playbackToWire(party.playback, serverMs),
    queue: queueToWire(party.playback),
    serverMs,
  };
}

const parties = new Map();
const syncState = {
  activePartyCode: null,
  serverUrl: `http://${PUBLIC_HOST}:${PORT}`,
  currentTrack: null,
  isPlaying: false,
  positionMs: 0,
  lyricTheme: 'classy',
  updatedAtMs: nowMs(),
  updatedBy: null,
};

function updateSyncFromParty(party, updatedByName) {
  syncState.activePartyCode = party.code;
  syncState.currentTrack = party.playback.track;
  syncState.isPlaying = party.playback.isPlaying;
  syncState.positionMs = positionAt(party.playback, nowMs());
  syncState.updatedAtMs = nowMs();
  if (updatedByName) syncState.updatedBy = updatedByName;
}

function clearSyncIfCode(code) {
  if (syncState.activePartyCode === code) {
    syncState.activePartyCode = null;
    syncState.currentTrack = null;
    syncState.isPlaying = false;
    syncState.positionMs = 0;
    syncState.updatedAtMs = nowMs();
  }
}

function createPartyRecord(maxMembers = 5, autoplayEnabled = false) {
  const code = generateCode(parties);
  const now = nowMs();
  const clampedMax = Math.max(2, Math.min(10, Number(maxMembers) || 5));
  const party = {
    code,
    createdAtMs: now,
    touchedAtMs: now,
    emptySinceMs: now,
    maxMembers: clampedMax,
    hostOnlyControl: false,
    members: new Map(),
    sockets: new Map(), // memberId -> Set<socket>
    playback: {
      seq: 0,
      track: null,
      queue: [],
      queueSeq: 0,
      queueIndex: -1,
      isPlaying: false,
      positionMs: 0,
      anchorMs: now,
      updatedBy: null,
      startedBy: null,
      startedByName: null,
      autoplayEnabled: Boolean(autoplayEnabled),
      updatedAtMs: now,
    },
  };
  parties.set(code, party);
  return party;
}

function joinMember(party, req) {
  const now = nowMs();
  for (const m of party.members.values()) {
    if (m.deviceId === req.deviceId) {
      m.displayName = req.displayName;
      m.avatarUrl = req.avatarUrl ?? null;
      m.userId = req.userId;
      m.lastSeenMs = now;
      m.token = crypto.randomBytes(24).toString('hex');
      party.touchedAtMs = now;
      return m;
    }
  }
  if (party.members.size >= party.maxMembers) {
    const err = new Error(`This party is full (${party.maxMembers} devices).`);
    err.status = 409;
    err.code = 'party_full';
    throw err;
  }
  const m = {
    memberId: crypto.randomBytes(8).toString('hex'),
    userId: req.userId,
    deviceId: req.deviceId,
    displayName: req.displayName,
    avatarUrl: req.avatarUrl ?? null,
    token: crypto.randomBytes(24).toString('hex'),
    isHost: party.members.size === 0,
    connected: false,
    joinedAtMs: now,
    lastSeenMs: now,
  };
  party.members.set(m.memberId, m);
  party.touchedAtMs = now;
  return m;
}

function authenticateMember(party, token) {
  if (!token) return null;
  for (const m of party.members.values()) {
    if (m.token === token) return m;
  }
  return null;
}

function refreshEmptiness(party) {
  let anyConn = false;
  for (const m of party.members.values()) {
    if (m.connected) {
      anyConn = true;
      break;
    }
  }
  if (anyConn) party.emptySinceMs = null;
  else if (party.emptySinceMs === null) party.emptySinceMs = nowMs();
}

function removeMember(party, memberId) {
  const removed = party.members.get(memberId);
  if (!removed) return null;
  party.members.delete(memberId);
  if (removed.isHost && party.members.size > 0) {
    const next = [...party.members.values()].sort((a, b) => a.joinedAtMs - b.joinedAtMs)[0];
    if (next) next.isHost = true;
  }
  party.touchedAtMs = nowMs();
  refreshEmptiness(party);
  return removed;
}

// ---- WebSocket framing (RFC 6455) ------------------------------------------

function encodeWsFrame(opcode, payloadBuf = Buffer.alloc(0)) {
  const len = payloadBuf.length;
  let header;
  if (len < 126) {
    header = Buffer.alloc(2);
    header[0] = 0x80 | opcode;
    header[1] = len;
  } else if (len < 65536) {
    header = Buffer.alloc(4);
    header[0] = 0x80 | opcode;
    header[1] = 126;
    header.writeUInt16BE(len, 2);
  } else {
    header = Buffer.alloc(10);
    header[0] = 0x80 | opcode;
    header[1] = 127;
    header.writeBigUInt64BE(BigInt(len), 2);
  }
  return Buffer.concat([header, payloadBuf]);
}

function sendWsJson(socket, obj) {
  if (!socket || socket.destroyed || !socket.writable) return;
  try {
    socket.write(encodeWsFrame(0x1, Buffer.from(JSON.stringify(obj), 'utf8')));
  } catch {
    // Socket closing.
  }
}

function broadcastParty(party, frame, excludeMemberId = null) {
  for (const [memberId, set] of party.sockets.entries()) {
    if (excludeMemberId && memberId === excludeMemberId) continue;
    for (const sock of set) sendWsJson(sock, frame);
  }
}

function closeMemberSockets(party, memberId) {
  const set = party.sockets.get(memberId);
  if (!set) return;
  for (const sock of set) {
    try {
      sock.write(encodeWsFrame(0x8));
      sock.end();
    } catch {
      sock.destroy();
    }
  }
  party.sockets.delete(memberId);
}

function membersFrame(party) {
  const ordered = [...party.members.values()].sort((a, b) => a.joinedAtMs - b.joinedAtMs);
  return {
    type: 'members',
    members: ordered.map(memberToWire),
    maxMembers: party.maxMembers,
    hostOnlyControl: party.hostOnlyControl,
    serverMs: nowMs(),
  };
}

function stateFrame(party) {
  const now = nowMs();
  return {
    type: 'state',
    playback: playbackToWire(party.playback, now),
    serverMs: now,
  };
}

function queueFrame(party) {
  return {
    type: 'queue',
    queue: queueToWire(party.playback),
    serverMs: nowMs(),
  };
}

function activityFrame(member, action, frame) {
  let detail = action;
  if (action === 'setTrack' && frame.track?.title) detail = `Changed the song to "${frame.track.title}"`;
  else if (action === 'queueAdd' && Array.isArray(frame.tracks) && frame.tracks.length > 0) {
    const title = frame.tracks[0]?.title;
    if (title) {
      detail =
        frame.tracks.length === 1
          ? `Added "${title}" to the queue`
          : `Added "${title}" and ${frame.tracks.length - 1} more to the queue`;
    }
  } else if (action === 'setQueue' && Array.isArray(frame.queue)) {
    detail = `Replaced the queue with ${frame.queue.length} tracks`;
  } else if (action === 'queueRemove') detail = 'Removed a track from the queue';
  else if (action === 'queueClear') detail = 'Cleared upcoming tracks';
  else if (action === 'queueMove') detail = 'Reordered the upcoming queue';
  else if (action === 'next') detail = 'Skipped to the next song';
  else if (action === 'previous') detail = 'Went back to the previous song';
  else if (action === 'play') detail = 'Started playback';
  else if (action === 'pause') detail = 'Paused playback';
  else if (action === 'seek') detail = 'Changed the playback position';
  else if (action === 'kick') detail = 'Removed a listener from the party';
  else if (action === 'setMaxMembers') detail = 'Changed the party size';
  else if (action === 'setAutoplay') detail = 'Changed AutoPlay';
  return {
    type: 'activity',
    action,
    by: member.displayName,
    detail,
    atMs: nowMs(),
  };
}

const CONTROL_ACTIONS = new Set([
  'play',
  'pause',
  'seek',
  'setTrack',
  'setQueue',
  'queueAdd',
  'queueRemove',
  'queueClear',
  'queueMove',
  'next',
  'previous',
  'setAutoplay',
]);

function applyControl(party, member, action, frame) {
  if (CONTROL_ACTIONS.has(action) && party.hostOnlyControl && !member.isHost) {
    return { ok: false, code: 'host_only', message: 'Only the host can control the music in this party.' };
  }
  const pb = party.playback;
  const now = nowMs();
  const posArg = typeof frame.positionMs === 'number' && Number.isFinite(frame.positionMs) ? Math.round(frame.positionMs) : null;

  switch (action) {
    case 'play': {
      const startAt = Math.max(0, posArg !== null ? posArg : positionAt(pb, now));
      pb.positionMs = startAt;
      pb.isPlaying = true;
      pb.anchorMs = now + PLAY_LEAD_MS;
      pb.seq++;
      pb.updatedBy = member.memberId;
      pb.updatedAtMs = now;
      updateSyncFromParty(party, member.displayName);
      return { ok: true };
    }
    case 'pause': {
      const pauseAt = Math.max(0, posArg !== null ? posArg : positionAt(pb, now));
      pb.positionMs = pauseAt;
      pb.isPlaying = false;
      pb.anchorMs = now;
      pb.seq++;
      pb.updatedBy = member.memberId;
      pb.updatedAtMs = now;
      updateSyncFromParty(party, member.displayName);
      return { ok: true };
    }
    case 'seek': {
      if (posArg === null) return { ok: false, code: 'missing_position', message: 'seek requires positionMs' };
      pb.positionMs = Math.max(0, posArg);
      pb.anchorMs = pb.isPlaying ? now + PLAY_LEAD_MS : now;
      pb.seq++;
      pb.updatedBy = member.memberId;
      pb.updatedAtMs = now;
      updateSyncFromParty(party, member.displayName);
      return { ok: true };
    }
    case 'setTrack': {
      const track = normalizeTrack(frame.track);
      if (!track) return { ok: false, code: 'invalid_track', message: 'Track must include videoId' };
      const pos = Math.max(0, posArg ?? 0);
      const isPlaying = typeof frame.isPlaying === 'boolean' ? frame.isPlaying : true;
      pb.track = track;
      pb.positionMs = pos;
      pb.isPlaying = isPlaying;
      pb.anchorMs = isPlaying ? now + PLAY_LEAD_MS : now;
      const qIdx = typeof frame.queueIndex === 'number' ? Math.trunc(frame.queueIndex) : null;
      if (qIdx !== null && qIdx >= 0 && qIdx < pb.queue.length && pb.queue[qIdx]?.videoId === track.videoId) {
        pb.queueIndex = qIdx;
      } else {
        pb.queueIndex = nearestIndexOf(pb.queue, track.videoId, pb.queueIndex);
      }
      pb.startedBy = member.memberId;
      pb.startedByName = member.displayName;
      pb.seq++;
      pb.updatedBy = member.memberId;
      pb.updatedAtMs = now;
      updateSyncFromParty(party, member.displayName);
      return { ok: true };
    }
    case 'setQueue': {
      const items = (Array.isArray(frame.queue) ? frame.queue : []).map(normalizeTrack).filter(Boolean);
      let qIdx = typeof frame.queueIndex === 'number' ? Math.trunc(frame.queueIndex) : -1;
      if (pb.track && (qIdx < 0 || qIdx >= items.length || items[qIdx]?.videoId !== pb.track.videoId)) {
        qIdx = nearestIndexOf(items, pb.track.videoId, qIdx);
      }
      if (qIdx >= 0 && qIdx < items.length) {
        pb.queue = items.slice(0, qIdx + 1 + MAX_UPCOMING_QUEUE);
        pb.queueIndex = qIdx;
      } else {
        pb.queue = items.slice(0, 60);
        pb.queueIndex = qIdx >= 0 && qIdx < pb.queue.length ? qIdx : -1;
      }
      pb.queueSeq++;
      pb.updatedBy = member.memberId;
      pb.updatedAtMs = now;
      return { ok: true };
    }
    case 'queueAdd': {
      const rawList = frame.track ? [frame.track] : Array.isArray(frame.tracks) ? frame.tracks : [];
      const tracksToAdd = rawList.map(normalizeTrack).filter(Boolean);
      const upcomingCount = pb.queueIndex >= 0 ? Math.max(0, pb.queue.length - 1 - pb.queueIndex) : pb.queue.length;
      const slotsLeft = MAX_UPCOMING_QUEUE - upcomingCount;
      if (slotsLeft <= 0) {
        return { ok: false, code: 'queue_full', message: `Queue is full (maximum ${MAX_UPCOMING_QUEUE} upcoming songs).` };
      }
      const waiting = new Set(pb.queue.slice(Math.max(0, pb.queueIndex + 1)).map((t) => t.videoId));
      const filtered = [];
      for (const tr of tracksToAdd) {
        if (tr.fromAutoplay) {
          if (waiting.has(tr.videoId)) continue;
          waiting.add(tr.videoId);
        }
        filtered.push(tr);
      }
      if (filtered.length === 0 && tracksToAdd.length > 0) return { ok: true };
      const finalAdd = filtered.slice(0, slotsLeft);
      if (finalAdd.length === 0) return { ok: false, code: 'no_tracks', message: 'Could not add track to queue.' };
      if (frame.playNext && pb.queueIndex >= 0 && pb.queueIndex < pb.queue.length) {
        pb.queue.splice(pb.queueIndex + 1, 0, ...finalAdd);
      } else {
        pb.queue.push(...finalAdd);
      }
      pb.queueSeq++;
      pb.updatedBy = member.memberId;
      pb.updatedAtMs = now;
      return { ok: true };
    }
    case 'queueRemove': {
      const vid = typeof frame.videoId === 'string' ? frame.videoId : '';
      const match = vid ? pb.queue.findIndex((t) => t.videoId === vid) : -1;
      if (match === -1 || match === pb.queueIndex) {
        return { ok: false, code: 'not_found', message: 'Track is not in the upcoming queue' };
      }
      pb.queue.splice(match, 1);
      if (pb.queueIndex > match) pb.queueIndex--;
      pb.queueSeq++;
      pb.updatedBy = member.memberId;
      pb.updatedAtMs = now;
      return { ok: true };
    }
    case 'queueClear': {
      if (pb.queueIndex >= 0) {
        if (pb.queue.length <= pb.queueIndex + 1) return { ok: false, code: 'no_upcoming', message: 'No upcoming tracks to clear' };
        pb.queue = pb.queue.slice(0, pb.queueIndex + 1);
      } else {
        if (pb.queue.length === 0) return { ok: false, code: 'no_upcoming', message: 'No upcoming tracks to clear' };
        pb.queue = [];
      }
      pb.queueSeq++;
      pb.updatedBy = member.memberId;
      pb.updatedAtMs = now;
      return { ok: true };
    }
    case 'queueMove': {
      let fromIdx = typeof frame.fromIndex === 'number' ? Math.trunc(frame.fromIndex) : -1;
      const toIdx = typeof frame.toIndex === 'number' ? Math.trunc(frame.toIndex) : -1;
      if (typeof frame.videoId === 'string' && frame.videoId) {
        const idx = pb.queue.findIndex((t) => t.videoId === frame.videoId);
        if (idx !== -1) fromIdx = idx;
      }
      if (fromIdx < 0 || fromIdx >= pb.queue.length || toIdx < 0 || toIdx >= pb.queue.length) {
        return { ok: false, code: 'invalid_move', message: 'Invalid queue move indices' };
      }
      if (fromIdx <= pb.queueIndex || toIdx <= pb.queueIndex) {
        return { ok: false, code: 'invalid_move', message: 'Cannot move played tracks' };
      }
      if (fromIdx !== toIdx) {
        const [item] = pb.queue.splice(fromIdx, 1);
        pb.queue.splice(toIdx, 0, item);
        pb.queueSeq++;
        pb.updatedBy = member.memberId;
        pb.updatedAtMs = now;
      }
      return { ok: true };
    }
    case 'next':
    case 'previous': {
      const target = pb.queueIndex + (action === 'next' ? 1 : -1);
      if (target < 0 || target >= pb.queue.length) {
        return {
          ok: false,
          code: action === 'next' ? 'end_of_queue' : 'start_of_queue',
          message: action === 'next' ? 'Already at the end of the queue.' : 'Already at the beginning of the queue.',
        };
      }
      pb.queueIndex = target;
      pb.track = pb.queue[target];
      pb.positionMs = 0;
      pb.isPlaying = true;
      pb.anchorMs = now + PLAY_LEAD_MS;
      pb.startedBy = member.memberId;
      pb.startedByName = member.displayName;
      pb.seq++;
      pb.updatedBy = member.memberId;
      pb.updatedAtMs = now;
      updateSyncFromParty(party, member.displayName);
      return { ok: true };
    }
    case 'setAutoplay': {
      if (typeof frame.enabled === 'boolean') pb.autoplayEnabled = frame.enabled;
      pb.updatedBy = member.memberId;
      pb.updatedAtMs = now;
      return { ok: true };
    }
    case 'setMaxMembers': {
      if (!member.isHost) return { ok: false, code: 'host_only', message: 'Only the host can change the party size.' };
      const val = Math.trunc(Number(frame.maxMembers));
      if (val < 2 || val > 10) return { ok: false, code: 'invalid_capacity', message: 'Party size must be between 2 and 10.' };
      if (val < party.members.size) return { ok: false, code: 'party_too_small', message: 'Party size cannot be smaller than the current member count.' };
      party.maxMembers = val;
      return { ok: true };
    }
    case 'setHostOnlyControl': {
      if (!member.isHost) return { ok: false, code: 'host_only', message: 'Only the host can change who controls the music.' };
      party.hostOnlyControl = Boolean(frame.enabled);
      return { ok: true };
    }
    case 'kick': {
      if (!member.isHost) return { ok: false, code: 'host_only', message: 'Only the host can remove listeners.' };
      const targetId = String(frame.memberId || '');
      if (!targetId || targetId === member.memberId) return { ok: false, code: 'invalid_member', message: 'Choose another listener to remove.' };
      if (!removeMember(party, targetId)) return { ok: false, code: 'not_found', message: 'That listener is no longer in this party.' };
      const set = party.sockets.get(targetId);
      if (set) {
        for (const s of set) sendWsJson(s, { type: 'bye', reason: 'kicked', message: 'The host removed you from this party.' });
      }
      closeMemberSockets(party, targetId);
      return { ok: true };
    }
    default:
      return { ok: false, code: 'unknown_action', message: `Unknown control action '${action}'` };
  }
}

// ---- HTTP Helpers & Routes --------------------------------------------------

function setCors(req, res) {
  const origin = req.headers.origin;
  res.setHeader('Access-Control-Allow-Origin', origin || '*');
  res.setHeader('Vary', 'Origin');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Authorization');
}

function sendJson(res, status, data) {
  const body = JSON.stringify(data);
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(body);
}

function sendError(res, status, code, message) {
  sendJson(res, status, { error: code, message });
}

function readJsonBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let total = 0;
    req.on('data', (c) => {
      total += c.length;
      if (total > 256 * 1024) {
        reject(new Error('body_too_large'));
        req.destroy();
        return;
      }
      chunks.push(c);
    });
    req.on('end', () => {
      try {
        const text = Buffer.concat(chunks).toString('utf8').trim();
        resolve(text ? JSON.parse(text) : {});
      } catch (e) {
        reject(e);
      }
    });
    req.on('error', reject);
  });
}

function parseBearer(req) {
  const auth = req.headers.authorization || '';
  const m = auth.match(/^Bearer\s+(.+)$/i);
  return m ? m[1].trim() : '';
}

function validateJoin(raw) {
  const userId = String(raw?.userId ?? '').trim();
  const deviceId = String(raw?.deviceId ?? '').trim();
  const displayName = String(raw?.displayName ?? '').trim();
  if (!userId || userId.length > 128) return 'userId must be between 1 and 128 characters';
  if (!deviceId || deviceId.length > 128) return 'deviceId must be between 1 and 128 characters';
  if (!displayName || displayName.length > 80) return 'displayName must be between 1 and 80 characters';
  return null;
}

function escapeHtml(s) {
  return String(s || '')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;');
}

const server = http.createServer(async (req, res) => {
  setCors(req, res);
  if (req.method === 'OPTIONS') {
    res.writeHead(204);
    res.end();
    return;
  }

  const url = new URL(req.url || '/', `http://${req.headers.host || 'localhost'}`);
  const pathname = url.pathname;

  try {
    if (req.method === 'GET' && pathname === '/') {
      return sendJson(res, 200, {
        ok: true,
        service: 'aurex-jam',
        parties: parties.size,
        serverMs: nowMs(),
      });
    }

    if (req.method === 'GET' && pathname === '/healthz') {
      return sendJson(res, 200, { ok: true, status: 'ok', service: 'aurex-jam', serverMs: nowMs() });
    }

    if (req.method === 'GET' && pathname === '/api/time') {
      return sendJson(res, 200, { serverMs: nowMs() });
    }

    if (req.method === 'GET' && pathname === '/api/sync/state') {
      return sendJson(res, 200, { ...syncState, serverMs: nowMs() });
    }

    if (req.method === 'POST' && pathname === '/api/sync/state') {
      const body = await readJsonBody(req);
      if (typeof body.activePartyCode === 'string' || body.activePartyCode === null) {
        syncState.activePartyCode = body.activePartyCode ? normaliseCode(body.activePartyCode) : null;
      }
      if (typeof body.serverUrl === 'string' && body.serverUrl) syncState.serverUrl = body.serverUrl;
      if (body.currentTrack !== undefined) syncState.currentTrack = normalizeTrack(body.currentTrack);
      if (typeof body.isPlaying === 'boolean') syncState.isPlaying = body.isPlaying;
      if (typeof body.positionMs === 'number') syncState.positionMs = Math.max(0, Math.round(body.positionMs));
      if (typeof body.lyricTheme === 'string') syncState.lyricTheme = body.lyricTheme;
      if (typeof body.updatedBy === 'string') syncState.updatedBy = body.updatedBy;
      syncState.updatedAtMs = nowMs();
      return sendJson(res, 200, { ok: true, state: syncState });
    }

    if (req.method === 'POST' && pathname === '/api/discord/share-jam') {
      const body = await readJsonBody(req);
      const webhookUrl = String(body.webhookUrl || '').trim();
      if (!/^https:\/\/(?:discord\.com|discordapp\.com)\/api\/webhooks\/.+/i.test(webhookUrl)) {
        return sendError(res, 422, 'invalid_webhook', 'Enter a valid Discord webhook URL (https://discord.com/api/webhooks/...).');
      }
      const code = normaliseCode(body.code || syncState.activePartyCode || '');
      if (!code) {
        return sendError(res, 400, 'missing_code', 'Start or join a Jam party first.');
      }
      const originHeader = `${url.protocol}//${req.headers.host || `${PUBLIC_HOST}:${PORT}`}`;
      const serverUrl = String(body.serverUrl || originHeader);
      const webBase = String(body.webUrl || `http://${PUBLIC_HOST}:${WEB_PORT}`).replace(/\/+$/, '');
      const webInvite = `${webBase}/invite/${code}?server=${encodeURIComponent(serverUrl)}`;
      const androidInvite = `bitchord://party/${code}?server=${encodeURIComponent(serverUrl)}`;
      const track = body.track || syncState.currentTrack;
      const trackText = track?.title
        ? `${track.title}${track.artist ? ` — ${track.artist}` : ''}`
        : 'Ready to play in queue';
      const hostName = String(body.hostName || syncState.updatedBy || 'Aurex Listener');

      const discordPayload = {
        username: 'Aurex Jam',
        embeds: [
          {
            title: `Live Aurex Jam — Room ${code}`,
            description: `Join synced playback on **Aurex Web** or **Aurex Android**.\n\n**Web Join:** ${webInvite}\n**Android Deep-Link:** \`${androidInvite}\``,
            color: 0xd4af37,
            fields: [
              { name: 'Room Code', value: `\`${code}\``, inline: true },
              { name: 'Host', value: hostName, inline: true },
              { name: 'Now Playing', value: trackText, inline: false },
            ],
            thumbnail: track?.thumbnailUrl ? { url: track.thumbnailUrl } : undefined,
            footer: { text: 'Aurex Cross-Device Listen Together' },
          },
        ],
      };

      const upstream = await fetch(webhookUrl, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(discordPayload),
      });
      if (!upstream.ok) {
        const msg = await upstream.text().catch(() => '');
        return sendError(res, 502, 'discord_rejected', `Discord webhook returned ${upstream.status}: ${msg.slice(0, 140)}`);
      }
      return sendJson(res, 200, { ok: true, code });
    }

    if (req.method === 'POST' && pathname === '/api/parties') {
      const body = await readJsonBody(req);
      const errMsg = validateJoin(body);
      if (errMsg) return sendError(res, 422, 'validation_error', errMsg);
      const party = createPartyRecord(body.maxMembers, body.autoplayEnabled);
      const member = joinMember(party, {
        userId: String(body.userId).trim(),
        deviceId: String(body.deviceId).trim(),
        displayName: String(body.displayName).trim(),
        avatarUrl: typeof body.avatarUrl === 'string' && body.avatarUrl.startsWith('http') ? body.avatarUrl : null,
      });
      updateSyncFromParty(party, member.displayName);
      return sendJson(res, 201, {
        code: party.code,
        token: member.token,
        you: memberToWire(member),
        party: partyToWire(party),
        serverMs: nowMs(),
      });
    }

    const joinMatch = pathname.match(/^\/api\/parties\/([^/]+)\/join$/);
    if (req.method === 'POST' && joinMatch) {
      const code = normaliseCode(decodeURIComponent(joinMatch[1]));
      const body = await readJsonBody(req);
      const errMsg = validateJoin(body);
      if (errMsg) return sendError(res, 422, 'validation_error', errMsg);
      const party = parties.get(code);
      if (!party) return sendError(res, 404, 'no_such_party', 'No party with that code.');
      const member = joinMember(party, {
        userId: String(body.userId).trim(),
        deviceId: String(body.deviceId).trim(),
        displayName: String(body.displayName).trim(),
        avatarUrl: typeof body.avatarUrl === 'string' && body.avatarUrl.startsWith('http') ? body.avatarUrl : null,
      });
      updateSyncFromParty(party, member.displayName);
      broadcastParty(party, membersFrame(party), member.memberId);
      return sendJson(res, 200, {
        code: party.code,
        token: member.token,
        you: memberToWire(member),
        party: partyToWire(party),
        serverMs: nowMs(),
      });
    }

    const previewMatch = pathname.match(/^\/api\/parties\/([^/]+)\/preview$/);
    if (req.method === 'GET' && previewMatch) {
      const code = normaliseCode(decodeURIComponent(previewMatch[1]));
      const party = parties.get(code);
      if (!party) return sendError(res, 404, 'no_such_party', 'No party with that code.');
      const ordered = [...party.members.values()].sort((a, b) => a.joinedAtMs - b.joinedAtMs);
      const host = ordered.find((m) => m.isHost);
      return sendJson(res, 200, {
        code: party.code,
        hostName: host?.displayName ?? '',
        memberCount: ordered.length,
        maxMembers: party.maxMembers,
        isFull: ordered.length >= party.maxMembers,
        members: ordered.map((m) => ({ displayName: m.displayName, avatarUrl: m.avatarUrl, isHost: m.isHost })),
      });
    }

    const leaveMatch = pathname.match(/^\/api\/parties\/([^/]+)\/leave$/);
    if (req.method === 'POST' && leaveMatch) {
      const code = normaliseCode(decodeURIComponent(leaveMatch[1]));
      const party = parties.get(code);
      if (!party) return sendError(res, 404, 'no_such_party', 'No party with that code.');
      const member = authenticateMember(party, parseBearer(req));
      if (!member) return sendError(res, 401, 'bad_token', 'This device is not a member of that party.');
      removeMember(party, member.memberId);
      closeMemberSockets(party, member.memberId);
      broadcastParty(party, membersFrame(party));
      if (party.members.size === 0) {
        parties.delete(party.code);
        clearSyncIfCode(party.code);
      }
      return sendJson(res, 200, { ok: true });
    }

    const partyMatch = pathname.match(/^\/api\/parties\/([^/]+)$/);
    if (req.method === 'GET' && partyMatch) {
      const code = normaliseCode(decodeURIComponent(partyMatch[1]));
      const party = parties.get(code);
      if (!party) return sendError(res, 404, 'no_such_party', 'No party with that code.');
      const member = authenticateMember(party, parseBearer(req));
      if (!member) return sendError(res, 401, 'bad_token', 'This device is not a member of that party.');
      return sendJson(res, 200, partyToWire(party));
    }

    const inviteMatch = pathname.match(/^\/invite\/([^/]+)$/);
    if (req.method === 'GET' && inviteMatch) {
      const code = normaliseCode(decodeURIComponent(inviteMatch[1]));
      const party = parties.get(code);
      const serverOrigin = `http://${req.headers.host || `${PUBLIC_HOST}:${PORT}`}`;
      const webInvite = `http://${PUBLIC_HOST}:${WEB_PORT}/invite/${encodeURIComponent(code)}?server=${encodeURIComponent(serverOrigin)}`;
      const deepLink = `bitchord://party/${encodeURIComponent(code)}?server=${encodeURIComponent(serverOrigin)}`;
      const trackText = party?.playback?.track?.title
        ? `${escapeHtml(party.playback.track.title)} — ${escapeHtml(party.playback.track.artist)}`
        : 'Ready in party';
      res.writeHead(party ? 200 : 404, { 'Content-Type': 'text/html; charset=utf-8' });
      res.end(`<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width,initial-scale=1" />
<title>Aurex Jam — ${escapeHtml(code)}</title>
<style>
  body { margin:0; font-family: system-ui, sans-serif; background:#0A0907; color:#F5ECD7; min-height:100vh; display:grid; place-items:center; padding:24px; }
  .card { max-width:440px; width:100%; background:#161411; border:1px solid rgba(212,175,55,0.28); border-radius:24px; padding:32px; text-align:center; }
  .code { font-size:40px; font-weight:800; letter-spacing:0.2em; color:#FFD470; margin:16px 0; }
  .btn { display:block; margin:12px 0; padding:14px 20px; border-radius:999px; font-weight:700; text-decoration:none; }
  .primary { background:#D4AF37; color:#0A0907; }
  .secondary { background:#27231D; color:#F5ECD7; }
  .meta { color:#B89F6D; font-size:14px; margin-bottom:20px; }
</style>
</head>
<body>
  <div class="card">
    <div style="font-size:13px;letter-spacing:0.12em;text-transform:uppercase;color:#D4AF37;">Aurex Listen Together</div>
    <div class="code">${escapeHtml(code)}</div>
    <div class="meta">${party ? `${party.members.size} listening · ${trackText}` : 'Party not found or expired'}</div>
    <a class="btn primary" href="${escapeHtml(deepLink)}">Open in Aurex Android App</a>
    <a class="btn secondary" href="${escapeHtml(webInvite)}">Join on Aurex Web</a>
  </div>
</body>
</html>`);
      return;
    }

    sendError(res, 404, 'not_found', 'Not found.');
  } catch (err) {
    const status = err.status || 500;
    sendError(res, status, err.code || 'server_error', err.message || 'Internal error');
  }
});

server.on('upgrade', (req, socket) => {
  const url = new URL(req.url || '/', `http://${req.headers.host || 'localhost'}`);
  const match = url.pathname.match(/^\/ws\/parties\/([^/]+)$/);
  if (!match) {
    socket.write('HTTP/1.1 404 Not Found\r\n\r\n');
    socket.destroy();
    return;
  }

  const code = normaliseCode(decodeURIComponent(match[1]));
  const party = parties.get(code);
  if (!party) {
    socket.write('HTTP/1.1 404 Not Found\r\n\r\n');
    socket.destroy();
    return;
  }

  let token = parseBearer(req);
  const protoHeader = String(req.headers['sec-websocket-protocol'] || '');
  const subprotocols = protoHeader
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean);
  for (const proto of subprotocols) {
    if (proto.startsWith('bitchord.token.') && !token) {
      token = proto.slice('bitchord.token.'.length);
    }
  }

  const member = authenticateMember(party, token);
  if (!member) {
    socket.write('HTTP/1.1 401 Unauthorized\r\n\r\n');
    socket.destroy();
    return;
  }

  const wsKey = req.headers['sec-websocket-key'];
  if (!wsKey) {
    socket.write('HTTP/1.1 400 Bad Request\r\n\r\n');
    socket.destroy();
    return;
  }

  const accept = crypto
    .createHash('sha1')
    .update(wsKey + WS_MAGIC_GUID)
    .digest('base64');

  const responseHeaders = [
    'HTTP/1.1 101 Switching Protocols',
    'Upgrade: websocket',
    'Connection: Upgrade',
    `Sec-WebSocket-Accept: ${accept}`,
  ];
  if (subprotocols.includes('bitchord.v1')) {
    responseHeaders.push('Sec-WebSocket-Protocol: bitchord.v1');
  }
  responseHeaders.push('', '');
  socket.write(responseHeaders.join('\r\n'));

  member.connected = true;
  member.lastSeenMs = nowMs();
  refreshEmptiness(party);

  if (!party.sockets.has(member.memberId)) party.sockets.set(member.memberId, new Set());
  party.sockets.get(member.memberId).add(socket);

  sendWsJson(socket, {
    type: 'welcome',
    you: memberToWire(member),
    party: partyToWire(party),
    serverMs: nowMs(),
  });
  broadcastParty(party, membersFrame(party), member.memberId);

  let buf = Buffer.alloc(0);
  socket.on('data', (chunk) => {
    buf = Buffer.concat([buf, chunk]);
    while (buf.length >= 2) {
      const b0 = buf[0];
      const b1 = buf[1];
      const opcode = b0 & 0x0f;
      const masked = (b1 & 0x80) !== 0;
      let len = b1 & 0x7f;
      let offset = 2;
      if (len === 126) {
        if (buf.length < 4) return;
        len = buf.readUInt16BE(2);
        offset = 4;
      } else if (len === 127) {
        if (buf.length < 10) return;
        len = Number(buf.readBigUInt64BE(2));
        offset = 10;
      }
      const maskLen = masked ? 4 : 0;
      if (buf.length < offset + maskLen + len) return;

      let payload = buf.subarray(offset + maskLen, offset + maskLen + len);
      if (masked) {
        const mask = buf.subarray(offset, offset + 4);
        const decoded = Buffer.alloc(len);
        for (let i = 0; i < len; i++) decoded[i] = payload[i] ^ mask[i % 4];
        payload = decoded;
      }
      buf = buf.subarray(offset + maskLen + len);

      if (opcode === 0x8) {
        socket.end(encodeWsFrame(0x8));
        return;
      }
      if (opcode === 0x9) {
        socket.write(encodeWsFrame(0xa, payload));
        continue;
      }
      if (opcode !== 0x1 && opcode !== 0x0) continue;

      let frame;
      try {
        frame = JSON.parse(payload.toString('utf8'));
      } catch {
        continue;
      }
      member.lastSeenMs = nowMs();
      switch (frame.type) {
        case 'ping':
          sendWsJson(socket, { type: 'pong', clientMs: frame.clientMs, serverMs: nowMs() });
          break;
        case 'sync':
          sendWsJson(socket, stateFrame(party));
          break;
        case 'syncQueue':
          sendWsJson(socket, queueFrame(party));
          break;
        case 'report':
          break;
        case 'control': {
          const queueBefore = party.playback.queueSeq;
          const resControl = applyControl(party, member, String(frame.action || ''), frame);
          if (resControl.ok) {
            party.touchedAtMs = nowMs();
            if (party.playback.queueSeq !== queueBefore) broadcastParty(party, queueFrame(party));
            broadcastParty(party, stateFrame(party));
            if (
              frame.action === 'kick' ||
              frame.action === 'setMaxMembers' ||
              frame.action === 'setHostOnlyControl'
            ) {
              broadcastParty(party, membersFrame(party));
            }
            broadcastParty(party, activityFrame(member, String(frame.action || ''), frame));
          } else {
            sendWsJson(socket, { type: 'error', error: resControl.code, message: resControl.message });
          }
          break;
        }
      }
    }
  });

  const cleanup = () => {
    const set = party.sockets.get(member.memberId);
    if (set) {
      set.delete(socket);
      if (set.size === 0) party.sockets.delete(member.memberId);
    }
    member.connected = Boolean(party.sockets.get(member.memberId)?.size);
    member.lastSeenMs = nowMs();
    refreshEmptiness(party);
    broadcastParty(party, membersFrame(party));
  };

  socket.on('close', cleanup);
  socket.on('error', cleanup);
});

setInterval(() => {
  const now = nowMs();
  for (const [code, party] of parties.entries()) {
    let membersChanged = false;
    for (const m of [...party.members.values()]) {
      if (!m.connected && now - m.lastSeenMs > DISCONNECT_GRACE_MS) {
        removeMember(party, m.memberId);
        membersChanged = true;
      }
    }
    if (
      now - party.createdAtMs > PARTY_MAX_AGE_MS ||
      (party.emptySinceMs !== null && now - party.emptySinceMs > EMPTY_PARTY_TTL_MS)
    ) {
      parties.delete(code);
      clearSyncIfCode(code);
      continue;
    }
    if (membersChanged) broadcastParty(party, membersFrame(party));
    if (party.sockets.size > 0) broadcastParty(party, stateFrame(party));
  }
}, HEARTBEAT_INTERVAL_MS);

server.listen(PORT, HOST, () => {
  console.log(`Aurex Jam & Sync Server listening on http://${HOST}:${PORT} (LAN http://${PUBLIC_HOST}:${PORT})`);
});
