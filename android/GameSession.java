package com.oceanpark;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

public final class GameSession {
    // Para Android emulator contra server local: ws://10.0.2.2:3000
    // Para desktop contra server local: ws://localhost:3000
    // Cambia esta URL cuando tengáis el servidor en Proxmox.
    public static String SERVER_URL = "ws://localhost:3000";
    public static final int MAX_PLAYERS = 8;

    private static final GameSession INSTANCE = new GameSession();

    public static GameSession get() {
        return INSTANCE;
    }

    public static final class PlayerState {
        public String id = "";
        public String nickname = "";
        public float x;
        public float y;
        public float vx;
        public float vy;
        public boolean facingRight = true;
        public boolean grounded;
        public boolean hasKey;
        public boolean crossedDoor;
        public int slot = 1;

        public PlayerState copy() {
            PlayerState p = new PlayerState();
            p.id = id;
            p.nickname = nickname;
            p.x = x;
            p.y = y;
            p.vx = vx;
            p.vy = vy;
            p.facingRight = facingRight;
            p.grounded = grounded;
            p.hasKey = hasKey;
            p.crossedDoor = crossedDoor;
            p.slot = slot;
            return p;
        }
    }

    public static final class WorldState {
        public boolean keyTaken;
        public String keyCarrierId = "";
        public float keyX = 45f;
        public float keyY = 261f;

        public boolean doorOpen;
        public float doorX = 450f;
        public float doorY = 550f;
        public float doorWidth = 32f;
        public float doorHeight = 64f;

        public boolean allPlayersPassed;
        public boolean shouldChangeScreen;
        public int totalPlayers;
        public int passedPlayers;
    }

    private final LinkedHashMap<String, PlayerState> players = new LinkedHashMap<>();
    private final WorldState world = new WorldState();

    private String requestedNickname = "Player";
    private String myNickname = "Player";
    private String myId = "";
    private String status = "Desconectado";
    private boolean connected;
    private boolean viewerMode;
    private GameWebSocketClient client;

    private GameSession() {
    }

    public synchronized void setRequestedNickname(String nickname) {
        requestedNickname = sanitizeNickname(nickname);
        if (requestedNickname.isEmpty()) {
            requestedNickname = "Player";
        }
    }

    public synchronized String getRequestedNickname() {
        return requestedNickname;
    }

    public synchronized String getMyNickname() {
        return myNickname == null || myNickname.isEmpty() ? requestedNickname : myNickname;
    }

    public synchronized String getMyId() {
        return myId;
    }

    public synchronized String getStatus() {
        return status;
    }

    public synchronized boolean isConnected() {
        return connected;
    }

    public synchronized boolean isViewerMode() {
        return viewerMode;
    }

    public synchronized void connect(String nickname) {
        setRequestedNickname(nickname);
        disconnect();
        viewerMode = false;
        status = "Conectando a " + SERVER_URL;
        client = new GameWebSocketClient(SERVER_URL, requestedNickname, false);
        client.connectAsync();
    }

    public synchronized void connectAsViewer() {
        disconnect();
        viewerMode = true;
        status = "Mirando sala";
        client = new GameWebSocketClient(SERVER_URL, "viewer", true);
        client.connectAsync();
    }

    public synchronized void disconnect() {
        if (client != null) {
            client.closeGracefully();
            client = null;
        }
        connected = false;
        viewerMode = false;
        myId = "";
        myNickname = "";
        players.clear();
        status = "Desconectado";
    }

    public synchronized void sendInput(float moveX, boolean jumpPressed, boolean jumpHeld) {
        if (client != null && connected) {
            client.sendInput(moveX, jumpPressed, jumpHeld);
        }
    }

    synchronized void onConnected() {
        connected = true;
        status = viewerMode ? "Conectado como visor" : "Conectado";
    }

    synchronized void onDisconnected(String message) {
        connected = false;
        status = message == null || message.trim().isEmpty() ? "Desconectado" : message;
        players.clear();
    }

    synchronized void setAssignedIdentity(String id, String nickname) {
        myId = id == null ? "" : id;
        myNickname = sanitizeNickname(nickname);
        if (myNickname.isEmpty()) {
            myNickname = requestedNickname;
        }
    }

    synchronized void replacePlayers(List<PlayerState> newPlayers) {
        players.clear();
        if (newPlayers == null) {
            return;
        }
        for (PlayerState p : newPlayers) {
            if (p == null || p.id == null || p.id.isEmpty()) {
                continue;
            }
            players.put(p.id, p.copy());
        }
        world.totalPlayers = players.size();
    }

    synchronized void updateWorld(WorldState newWorld) {
        if (newWorld == null) {
            return;
        }
        world.keyTaken = newWorld.keyTaken;
        world.keyCarrierId = safe(newWorld.keyCarrierId);
        world.keyX = newWorld.keyX;
        world.keyY = newWorld.keyY;
        world.doorOpen = newWorld.doorOpen;
        world.doorX = newWorld.doorX;
        world.doorY = newWorld.doorY;
        world.doorWidth = newWorld.doorWidth;
        world.doorHeight = newWorld.doorHeight;
        world.allPlayersPassed = newWorld.allPlayersPassed;
        world.shouldChangeScreen = newWorld.shouldChangeScreen;
        world.totalPlayers = newWorld.totalPlayers;
        world.passedPlayers = newWorld.passedPlayers;
    }

    public synchronized List<PlayerState> snapshotPlayers() {
        ArrayList<PlayerState> copy = new ArrayList<>();
        for (PlayerState p : players.values()) {
            copy.add(p.copy());
        }
        return copy;
    }

    public synchronized WorldState snapshotWorld() {
        WorldState w = new WorldState();
        w.keyTaken = world.keyTaken;
        w.keyCarrierId = world.keyCarrierId;
        w.keyX = world.keyX;
        w.keyY = world.keyY;
        w.doorOpen = world.doorOpen;
        w.doorX = world.doorX;
        w.doorY = world.doorY;
        w.doorWidth = world.doorWidth;
        w.doorHeight = world.doorHeight;
        w.allPlayersPassed = world.allPlayersPassed;
        w.shouldChangeScreen = world.shouldChangeScreen;
        w.totalPlayers = world.totalPlayers;
        w.passedPlayers = world.passedPlayers;
        return w;
    }

    public static String sanitizeNickname(String nickname) {
        if (nickname == null) {
            return "Player";
        }
        String trimmed = nickname.trim();
        if (trimmed.isEmpty()) {
            return "Player";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < trimmed.length() && out.length() < 16; i++) {
            char c = trimmed.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-') {
                out.append(c);
            }
        }
        return out.length() == 0 ? "Player" : out.toString();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
