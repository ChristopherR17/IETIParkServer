package com.oceanpark;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.github.czyzby.websocket.WebSocket;
import com.github.czyzby.websocket.WebSocketListener;
import com.github.czyzby.websocket.WebSockets;

import java.util.ArrayList;
import java.util.List;

public final class GameWebSocketClient {
    private final String url;
    private final String nickname;
    private final boolean viewer;
    private WebSocket socket;

    public GameWebSocketClient(String url, String nickname, boolean viewer) {
        this.url = url;
        this.nickname = GameSession.sanitizeNickname(nickname);
        this.viewer = viewer;
    }

    public void connectAsync() {
        try {
            socket = WebSockets.newSocket(url);
            socket.setSendGracefully(true);
            socket.setSerializeAsString(true);
            socket.addListener(new WebSocketListener() {
                @Override
                public boolean onOpen(WebSocket webSocket) {
                    Gdx.app.postRunnable(new Runnable() {
                        @Override
                        public void run() {
                            GameSession.get().onConnected();
                        }
                    });

                    if (viewer) {
                        sendRaw("{\"type\":\"JOIN\",\"nickname\":\"viewer\",\"client\":\"viewer\",\"viewer\":true}");
                    } else {
                        sendRaw("{\"type\":\"JOIN\",\"nickname\":\"" + escape(nickname) + "\",\"client\":\"libgdx\"}");
                    }
                    return WebSocketListener.FULLY_HANDLED;
                }

                @Override
                public boolean onClose(WebSocket webSocket, int closeCode, String reason) {
                    final String msg = reason == null || reason.trim().isEmpty()
                        ? "Desconectado del servidor"
                        : "Desconectado: " + reason;
                    Gdx.app.postRunnable(new Runnable() {
                        @Override
                        public void run() {
                            GameSession.get().onDisconnected(msg);
                        }
                    });
                    return WebSocketListener.FULLY_HANDLED;
                }

                @Override
                public boolean onMessage(WebSocket webSocket, String packet) {
                    handleTextMessage(packet);
                    return WebSocketListener.FULLY_HANDLED;
                }

                @Override
                public boolean onMessage(WebSocket webSocket, byte[] packet) {
                    if (packet != null) {
                        handleTextMessage(new String(packet));
                    }
                    return WebSocketListener.FULLY_HANDLED;
                }

                @Override
                public boolean onError(WebSocket webSocket, final Throwable error) {
                    Gdx.app.error("GameWebSocketClient", "Error WebSocket", error);
                    Gdx.app.postRunnable(new Runnable() {
                        @Override
                        public void run() {
                            String detail = error == null ? "desconocido" : error.getMessage();
                            GameSession.get().onDisconnected("Error WebSocket: " + detail);
                        }
                    });
                    return WebSocketListener.FULLY_HANDLED;
                }
            });

            socket.connect();
        } catch (final Exception ex) {
            Gdx.app.error("GameWebSocketClient", "No se pudo iniciar WebSocket", ex);
            Gdx.app.postRunnable(new Runnable() {
                @Override
                public void run() {
                    GameSession.get().onDisconnected("No se pudo conectar: " + ex.getMessage());
                }
            });
        }
    }

    public boolean isOpen() {
        return socket != null && socket.isOpen();
    }

    public void sendInput(float moveX, boolean jumpPressed, boolean jumpHeld) {
        int mx = moveX < -0.12f ? -1 : (moveX > 0.12f ? 1 : 0);
        sendRaw("{\"type\":\"INPUT\",\"moveX\":" + mx
            + ",\"jumpPressed\":" + jumpPressed
            + ",\"jumpHeld\":" + jumpHeld + "}");
    }

    public void closeGracefully() {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (Exception ignored) {
        }
        socket = null;
    }

    private void sendRaw(String text) {
        try {
            if (socket != null && socket.isOpen()) {
                socket.send(text);
            }
        } catch (Exception ex) {
            Gdx.app.error("GameWebSocketClient", "No se pudo enviar mensaje", ex);
        }
    }

    private void handleTextMessage(final String packet) {
        if (packet == null || packet.trim().isEmpty()) {
            return;
        }

        Gdx.app.postRunnable(new Runnable() {
            @Override
            public void run() {
                parseAndApply(packet);
            }
        });
    }

    private void parseAndApply(String packet) {
        try {
            JsonValue root = new JsonReader().parse(packet);
            String type = root.getString("type", "");

            if ("WELCOME".equalsIgnoreCase(type) || "ASSIGNED".equalsIgnoreCase(type)) {
                String id = root.getString("id", root.getString("playerId", ""));
                String nick = root.getString("nickname", nickname);
                GameSession.get().setAssignedIdentity(id, nick);
                return;
            }

            if ("STATE".equalsIgnoreCase(type) || "ROOM_STATE".equalsIgnoreCase(type) || "GAME_STATE".equalsIgnoreCase(type)) {
                parseState(root);
            }
        } catch (Exception ex) {
            Gdx.app.error("GameWebSocketClient", "Mensaje inválido: " + packet, ex);
        }
    }

    private void parseState(JsonValue root) {
        List<GameSession.PlayerState> players = new ArrayList<>();

        JsonValue playersNode = root.get("players");
        if (playersNode != null && playersNode.isArray()) {
            int slot = 1;
            for (JsonValue p = playersNode.child; p != null; p = p.next) {
                GameSession.PlayerState state = new GameSession.PlayerState();
                state.id = p.getString("id", p.getString("playerId", ""));
                state.nickname = p.getString("nickname", "Player");
                state.x = p.getFloat("x", 127f);
                state.y = p.getFloat("y", 386f);
                state.vx = p.getFloat("vx", 0f);
                state.vy = p.getFloat("vy", 0f);
                state.facingRight = p.getBoolean("facingRight", true);
                state.grounded = p.getBoolean("grounded", false);
                state.hasKey = p.getBoolean("hasKey", false);
                state.crossedDoor = p.getBoolean("crossedDoor", false);
                state.slot = p.getInt("slot", slot);
                players.add(state);
                slot++;
            }
        }

        GameSession.get().replacePlayers(players);

        JsonValue worldNode = root.get("world");
        if (worldNode != null && worldNode.isObject()) {
            GameSession.WorldState world = new GameSession.WorldState();
            world.keyTaken = worldNode.getBoolean("keyTaken", false);
            world.keyCarrierId = worldNode.getString("keyCarrierId", "");
            world.keyX = worldNode.getFloat("keyX", 45f);
            world.keyY = worldNode.getFloat("keyY", 261f);
            world.doorOpen = worldNode.getBoolean("doorOpen", false);
            world.doorX = worldNode.getFloat("doorX", 450f);
            world.doorY = worldNode.getFloat("doorY", 550f);
            world.doorWidth = worldNode.getFloat("doorWidth", 32f);
            world.doorHeight = worldNode.getFloat("doorHeight", 64f);
            world.allPlayersPassed = worldNode.getBoolean("allPlayersPassed", false);
            world.shouldChangeScreen = worldNode.getBoolean("shouldChangeScreen", false);
            world.totalPlayers = worldNode.getInt("totalPlayers", players.size());
            world.passedPlayers = worldNode.getInt("passedPlayers", 0);
            GameSession.get().updateWorld(world);
        }
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
