# Ocean Park Server

Servidor NodeJS + WebSocket adaptado a la temática de cueva/Ocean Park.

## Archivos principales

- `websocketServer.js`: servidor HTTP + WebSocket.
- `gameLogic.js`: lógica del mapa, jugadores, llave hoja, puerta, suelos, caída y cambio de pantalla.
- `mongo.js`: persistencia en MongoDB para jugadores, categorías, partidas y records.
- `.env.example`: configuración de ejemplo.

## Instalación

```bash
npm install
cp .env.example .env
npm run server
```

## Mensajes WebSocket

### Unirse como jugador

```json
{ "type": "JOIN", "nickname": "MewPlayer" }
```

### Unirse como visor Flutter/Web

```json
{ "type": "JOIN", "viewer": true, "client": "flutter" }
```

### Movimiento avanzado

```json
{ "type": "INPUT", "moveX": 1, "jumpPressed": false, "jumpHeld": false }
```

`moveX` acepta valores entre `-1` y `1`.

### Movimiento simple

```json
{ "type": "MOVE", "dir": "LEFT" }
```

Valores: `LEFT`, `RIGHT`, `JUMP`, `STOP`.

## Estado enviado al cliente

El servidor envía continuamente mensajes `STATE` con:

- `players`: jugadores conectados con posición, animación, dirección, si tienen la llave y si han cruzado la puerta.
- `world`: estado del mapa, llave, puerta, plataformas, zona de muerte y objetivo del nivel.

También se mantienen alias antiguos como `cat`, `hasPotion`, `potionTaken`, etc. para que un cliente basado en el proyecto de Laura no se rompa mientras se adapta a `key`/`door`.
