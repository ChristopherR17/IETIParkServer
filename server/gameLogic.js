const MAX_PLAYERS = 8;
const FPS = 30;
const DT = 1 / FPS;

// Servidor adaptado a vuestro mapa de cueva/Ocean Park.
// Sistema de coordenadas: x/y crecen hacia la derecha/abajo.
// La posición del jugador representa el centro inferior del sprite.
const map = {
  name: 'Ocean World',
  width: 520,
  height: 460,
  deathY: 436
};

const playerBody = {
  w: 24,
  h: 30,
  speed: 95,
  gravity: 900,
  jump: 315,
  maxFall: 540
};

// Sprites del editor. leaf_key en game_data.json está en x:45 y:261, tamaño 32x32.
// Guardamos la llave por centro, igual que se envía al cliente.
const keyStart = { x: 61, y: 277, w: 22, h: 22 };

// Door Closed en game_data.json está en x:260 y:379, tamaño 54x38.
const doorStart = { x: 260, y: 379, w: 54, h: 38 };

// Zonas Floor exportadas de level_000_zones.json.
const floorZones = [
  { name: 'Suelo_1', x: 42, y: 402, w: 246, h: 13 },
  { name: 'Suelo_2', x: 335, y: 401, w: 168, h: 14 },
  { name: 'Plataforma_central', x: 150, y: 325, w: 62, h: 14 },
  { name: 'Plataforma_llave', x: 58, y: 278, w: 47, h: 14 },
  { name: 'Escalon_1', x: 104, y: 278, w: 16, h: 14 },
  { name: 'Escalon_2', x: 120, y: 294, w: 16, h: 14 },
  { name: 'Escalon_3', x: 42, y: 264, w: 16, h: 14 },
  { name: 'Escalon_4', x: 26, y: 249, w: 16, h: 14 },
  { name: 'Escalon_5', x: 134, y: 309, w: 16, h: 14 }
];

const deathZone = { name: 'Player Death', x: -231, y: 436, w: 1664, h: 153 };

// Spawns sobre el primer suelo. Separados para evitar solapamientos iniciales.
const spawns = [
  { x: 64, y: 402 }, { x: 92, y: 402 }, { x: 120, y: 402 }, { x: 148, y: 402 },
  { x: 176, y: 402 }, { x: 204, y: 402 }, { x: 232, y: 402 }, { x: 260, y: 402 }
];

function clamp(v, min, max) {
  return Math.max(min, Math.min(max, v));
}

function round(v) {
  return Math.round(v * 100) / 100;
}

function rectsTouch(a, b) {
  const EPS = 0.001;
  return a.x < b.x + b.w - EPS &&
    a.x + a.w > b.x + EPS &&
    a.y < b.y + b.h - EPS &&
    a.y + a.h > b.y + EPS;
}

function cleanNick(value) {
  const nick = String(value || 'Player').trim().replace(/[^A-Za-z0-9_-]/g, '_').slice(0, 16);
  return nick || 'Player';
}

function isViewer(msg) {
  const text = String(msg.client || msg.role || '').toLowerCase();
  return msg.viewer === true || text.includes('viewer') || text.includes('flutter') || text.includes('web');
}

class GameRoom {
  constructor({ log, mongo }) {
    this.log = log;
    this.mongo = mongo || {};
    this.players = new Map();
    this.nextId = 1;

    this.key = { ...keyStart, taken: false, carrierId: null, consumed: false };
    this.door = { ...doorStart, open: false, openedAt: 0 };
    this.goal = {
      unlocked: false,
      allPlayersPassed: false,
      shouldChangeScreen: false,
      crossedAt: 0,
      changeReason: ''
    };

    this.doorCrossX = this.door.x + this.door.w;
  }

  getPlayerCount() {
    return this.players.size;
  }

  isFull() {
    return this.players.size >= MAX_PLAYERS;
  }

  freeNick(base) {
    const used = new Set([...this.players.values()].map(p => p.nickname));
    const nick = cleanNick(base);
    if (!used.has(nick)) return nick;

    let i = 1;
    while (used.has(`${nick}_${i}`)) i++;
    return `${nick}_${i}`;
  }

  freeSkin() {
    const used = new Set([...this.players.values()].map(p => p.skin));
    for (let i = 1; i <= MAX_PLAYERS; i++) {
      if (!used.has(i)) return i;
    }
    return 1;
  }

  async addPlayer(msg, previousId = null) {
    const id = previousId || `p${this.nextId++}`;
    const skin = this.freeSkin();
    const spawn = spawns[skin - 1] || spawns[0];
    const nickname = this.freeNick(msg.nickname);

    if (this.players.size === 0 && this.mongo.startMatch) {
      await this.mongo.startMatch();
    }

    const jugadorDoc = this.mongo.upsertJugador
      ? await this.mongo.upsertJugador(nickname)
      : null;

    const player = {
      id,
      nickname,
      skin,
      // Alias para no romper clientes que todavía esperan el campo cat.
      cat: skin,
      x: spawn.x,
      y: spawn.y,
      spawnX: spawn.x,
      spawnY: spawn.y,
      vx: 0,
      vy: 0,
      grounded: true,
      facingRight: true,
      anim: 'idle',
      crossedDoor: false,
      mongoId: jugadorDoc ? jugadorDoc._id : null,
      input: { moveX: 0, jumpPressed: false, jumpHeld: false }
    };

    this.players.set(id, player);

    if (this.mongo.registerPlayerInMatch) {
      await this.mongo.registerPlayerInMatch(player.mongoId);
    }

    this.log.info(`${player.nickname} entra como Mew${player.skin}`);
    return player;
  }

  removePlayer(id, reason) {
    const player = this.players.get(id);
    if (!player) return false;

    this.players.delete(id);

    if (this.key.carrierId === id) {
      this.resetWorld();
    }

    this.log.info(`${player.nickname} sale (${reason})`);

    if (this.players.size === 0) {
      this.resetWorld();
    }

    return true;
  }

  resetWorld() {
    this.key.taken = false;
    this.key.carrierId = null;
    this.key.consumed = false;

    this.door.open = false;
    this.door.openedAt = 0;

    this.goal.unlocked = false;
    this.goal.allPlayersPassed = false;
    this.goal.shouldChangeScreen = false;
    this.goal.crossedAt = 0;
    this.goal.changeReason = '';

    for (const player of this.players.values()) {
      player.crossedDoor = false;
    }
  }

  resetPlayersAndWorld() {
    this.players.clear();
    this.resetWorld();
  }

  setInput(playerId, msg) {
    const player = this.players.get(playerId);
    if (!player) return;

    player.input.moveX = clamp(Number(msg.moveX || 0), -1, 1);
    player.input.jumpPressed = Boolean(msg.jumpPressed) || player.input.jumpPressed;
    player.input.jumpHeld = Boolean(msg.jumpHeld);
  }

  setMoveInput(playerId, msg) {
    const player = this.players.get(playerId);
    if (!player) return;

    const dir = String(msg.dir || '').toUpperCase();
    player.input.moveX = dir === 'LEFT' ? -1 : (dir === 'RIGHT' ? 1 : 0);
    player.input.jumpPressed = dir === 'JUMP' || Boolean(msg.jumpPressed);
  }

  tick() {
    for (const player of this.players.values()) {
      this.updatePlayer(player);
    }
    this.updateGoalState();
  }

  updatePlayer(player) {
    const input = player.input || { moveX: 0, jumpPressed: false, jumpHeld: false };
    const move = clamp(Number(input.moveX || 0), -1, 1);

    player.vx = move * playerBody.speed;
    if (move < 0) player.facingRight = false;
    if (move > 0) player.facingRight = true;

    player.grounded = this.isStandingOnSomething(player);

    if (input.jumpPressed && player.grounded) {
      player.vy = -playerBody.jump;
      player.grounded = false;
    }
    input.jumpPressed = false;

    if (!player.grounded) {
      player.vy = Math.min(playerBody.maxFall, player.vy + playerBody.gravity * DT);
    }

    this.moveX(player, player.vx * DT);
    this.moveY(player, player.vy * DT);

    const playerRect = this.playerRect(player);

    if (!this.key.taken && !this.key.consumed && rectsTouch(playerRect, this.keyRect())) {
      this.key.taken = true;
      this.key.carrierId = player.id;
      this.log.info(`${player.nickname} coge la llave hoja`);

      if (player.mongoId && this.mongo.markKeyObtained) {
        this.mongo.markKeyObtained(player.mongoId).catch(() => {});
      }
    }

    if (!this.door.open && this.key.carrierId === player.id && rectsTouch(playerRect, this.doorRect())) {
      this.openDoorWithKey(player);
    }

    if (rectsTouch(playerRect, deathZone) || player.y > map.deathY) {
      this.respawnPlayer(player);
    }

    player.x = clamp(player.x, playerBody.w * 0.5, map.width - playerBody.w * 0.5);
    player.anim = !player.grounded ? 'jump' : (Math.abs(player.vx) > 1 ? 'run' : 'idle');
  }

  openDoorWithKey(player) {
    this.key.taken = true;
    this.key.consumed = true;
    this.key.carrierId = null;

    this.door.open = true;
    this.door.openedAt = Date.now();

    this.goal.unlocked = true;
    this.goal.allPlayersPassed = false;
    this.goal.shouldChangeScreen = false;
    this.goal.changeReason = '';

    for (const p of this.players.values()) {
      p.crossedDoor = false;
    }

    this.log.info(`${player.nickname} abre la puerta con la llave hoja`);
  }

  respawnPlayer(player) {
    player.x = player.spawnX;
    player.y = player.spawnY;
    player.vx = 0;
    player.vy = 0;
    player.grounded = true;

    if (this.key.carrierId === player.id) {
      this.key.taken = false;
      this.key.carrierId = null;
      this.key.consumed = false;
    }

    this.log.info(`${player.nickname} reaparece al inicio`);
  }

  updateGoalState() {
    if (!this.goal.unlocked) {
      this.goal.allPlayersPassed = false;
      this.goal.shouldChangeScreen = false;
      return;
    }

    for (const player of this.players.values()) {
      const playerLeft = player.x - playerBody.w * 0.5;
      if (!player.crossedDoor && playerLeft >= this.doorCrossX) {
        player.crossedDoor = true;
        this.log.info(`${player.nickname} ha cruzado la puerta`);
      }
    }

    const hasPlayers = this.players.size > 0;
    const everyonePassed = hasPlayers && [...this.players.values()].every(p => p.crossedDoor === true);

    if (everyonePassed && !this.goal.shouldChangeScreen) {
      this.goal.allPlayersPassed = true;
      this.goal.shouldChangeScreen = true;
      this.goal.crossedAt = Date.now();
      this.goal.changeReason = 'ALL_PLAYERS_CROSSED_DOOR';
      this.log.info('Todos los jugadores han cruzado la puerta. La app puede cambiar de pantalla.');

      if (this.mongo.finishMatch) {
        this.mongo.finishMatch().catch(() => {});
      }
      return;
    }

    if (!everyonePassed) {
      this.goal.allPlayersPassed = false;
      this.goal.shouldChangeScreen = false;
      this.goal.changeReason = '';
    }
  }

  moveX(player, dx) {
    if (dx === 0) return;

    let nextX = player.x + dx;
    let rect = this.playerRect(player, nextX, player.y);

    if (this.touchesClosedDoorWithKey(player, rect)) {
      this.openDoorWithKey(player);
    }

    for (const box of this.collisionBoxes(player)) {
      if (!rectsTouch(rect, box)) continue;

      if (dx > 0) nextX = box.x - playerBody.w * 0.5;
      else nextX = box.x + box.w + playerBody.w * 0.5;

      player.vx = 0;
      rect = this.playerRect(player, nextX, player.y);
    }

    player.x = clamp(nextX, playerBody.w * 0.5, map.width - playerBody.w * 0.5);
  }

  moveY(player, dy) {
    if (dy === 0) return;

    let nextY = player.y + dy;
    let rect = this.playerRect(player, player.x, nextY);
    player.grounded = false;

    if (this.touchesClosedDoorWithKey(player, rect)) {
      this.openDoorWithKey(player);
    }

    for (const box of this.collisionBoxes(player)) {
      if (!rectsTouch(rect, box)) continue;

      if (dy > 0) {
        nextY = box.y;
        player.grounded = true;
      } else {
        nextY = box.y + box.h + playerBody.h;
      }

      player.vy = 0;
      rect = this.playerRect(player, player.x, nextY);
    }

    player.y = nextY;
  }

  isStandingOnSomething(player) {
    const foot = {
      x: player.x - playerBody.w * 0.5 + 1,
      y: player.y,
      w: playerBody.w - 2,
      h: 1.5
    };

    for (const box of this.collisionBoxes(player)) {
      const top = { x: box.x, y: box.y - 0.5, w: box.w, h: 1.5 };
      if (rectsTouch(foot, top)) return true;
    }
    return false;
  }

  touchesClosedDoorWithKey(player, rect) {
    return !this.door.open && !this.key.consumed && this.key.carrierId === player.id && rectsTouch(rect, this.doorRect());
  }

  collisionBoxes(player) {
    return [...this.mapBoxes(), ...this.playerBoxes(player)];
  }

  mapBoxes() {
    const boxes = [
      { name: 'left_wall', x: -50, y: -200, w: 50, h: 800 },
      { name: 'right_wall', x: map.width, y: -200, w: 50, h: 800 },
      ...floorZones
    ];

    if (!this.door.open) {
      boxes.push(this.doorRect());
    }

    return boxes;
  }

  playerBoxes(player) {
    const boxes = [];
    for (const other of this.players.values()) {
      if (other.id !== player.id) boxes.push({ name: 'player', ...this.playerRect(other) });
    }
    return boxes;
  }

  playerRect(player, x = player.x, y = player.y) {
    return { x: x - playerBody.w * 0.5, y: y - playerBody.h, w: playerBody.w, h: playerBody.h };
  }

  keyRect() {
    return { x: this.key.x - this.key.w * 0.5, y: this.key.y - this.key.h * 0.5, w: this.key.w, h: this.key.h };
  }

  doorRect() {
    return { name: 'door', x: this.door.x, y: this.door.y, w: this.door.w, h: this.door.h };
  }

  countPlayersPastDoor() {
    let total = 0;
    for (const p of this.players.values()) {
      if (p.crossedDoor === true) total++;
    }
    return total;
  }

  playersForClient() {
    return [...this.players.values()].map(p => ({
      id: p.id,
      nickname: p.nickname,
      skin: p.skin,
      cat: p.cat,
      x: round(p.x),
      y: round(p.y),
      vx: round(p.vx),
      vy: round(p.vy),
      anim: p.anim,
      facingRight: p.facingRight,
      grounded: p.grounded,
      hasKey: this.key.carrierId === p.id,
      // Alias temporal para clientes antiguos.
      hasPotion: this.key.carrierId === p.id,
      crossedDoor: p.crossedDoor === true,
      viewer: false
    }));
  }

  crossedPlayersForClient() {
    return [...this.players.values()].map(p => ({
      id: p.id,
      nickname: p.nickname,
      crossedDoor: p.crossedDoor === true
    }));
  }

  worldForClient() {
    return {
      mapName: map.name,
      mapWidth: map.width,
      mapHeight: map.height,
      floors: floorZones,
      deathZone,

      keyTaken: this.key.taken,
      keyConsumed: this.key.consumed,
      keyCarrierId: this.key.carrierId || '',
      keyX: this.key.x,
      keyY: this.key.y,
      keyWidth: this.key.w,
      keyHeight: this.key.h,

      // Alias temporal para clientes basados en el server de Laura.
      potionTaken: this.key.taken,
      potionConsumed: this.key.consumed,
      potionCarrierId: this.key.carrierId || '',
      potionX: this.key.x,
      potionY: this.key.y,

      doorOpen: this.door.open,
      doorOpening: this.door.open && Date.now() - this.door.openedAt < 1100,
      doorX: this.door.x,
      doorY: this.door.y,
      doorWidth: this.door.w,
      doorHeight: this.door.h,

      levelUnlocked: this.goal.unlocked,
      allPlayersPassed: this.goal.allPlayersPassed,
      shouldChangeScreen: this.goal.shouldChangeScreen,
      crossedPlayers: this.crossedPlayersForClient(),
      totalPlayers: this.players.size,
      passedPlayers: this.countPlayersPastDoor(),
      changeReason: this.goal.changeReason
    };
  }
}

module.exports = {
  GameRoom,
  MAX_PLAYERS,
  FPS,
  isViewer
};
