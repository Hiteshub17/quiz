package com.hitesh.quiz;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * Manages all live in-memory state and communication for a single quiz room.
 */
public class GameRoom {

    /** Room code shown on the projector (e.g. "ABCD"). */
    private final String roomCode;

    /** Secret token given only to the host who created the room. */
    private final String hostToken;

    /** Registered players keyed by playerId. */
    private final Map<String, Player> players = new ConcurrentHashMap<>();

    /** Active SSE connection for the host screen. */
    private volatile SseEmitter hostEmitter;

    /** Single scheduler for room heartbeats and question countdowns. */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    /** Lifecycle state of the room. */
    public enum State { LOBBY, QUESTION, RESULTS, FINISHED }
    private volatile State state = State.LOBBY;

    /**
     * Inner model for a player in this room.
     */
    public static class Player {
        private final String id;
        private final String name;
        private int score = 0;
        private volatile Integer lastAnswer = null; // 0-3 option index
        private volatile long answeredAt = 0;

        public Player(String id, String name) {
            this.id = id;
            this.name = name;
        }

        public String getId() { return id; }
        public String getName() { return name; }
        public int getScore() { return score; }
        public void addScore(int points) { this.score += points; }
        public Integer getLastAnswer() { return lastAnswer; }
        public void setLastAnswer(Integer ans, long time) { this.lastAnswer = ans; this.answeredAt = time; }
        public long getAnsweredAt() { return answeredAt; }
        public void resetAnswer() { this.lastAnswer = null; this.answeredAt = 0; }
    }

    public GameRoom(String roomCode) {
        this.roomCode = roomCode;
        this.hostToken = UUID.randomUUID().toString();

        // Start ~15s heartbeat to keep mobile and host SSE connections alive
        scheduler.scheduleAtFixedRate(this::sendHeartbeat, 15, 15, TimeUnit.SECONDS);
    }

    public String getRoomCode() {
        return roomCode;
    }

    public String getHostToken() {
        return hostToken;
    }

    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    public int getPlayerCount() {
        return players.size();
    }

    public Map<String, Player> getPlayers() {
        return players;
    }

    public Player getPlayer(String playerId) {
        return players.get(playerId);
    }

    /**
     * Registers a new player into the room.
     * Rejects empty names and duplicate names (case-insensitive).
     */
    public synchronized String addPlayer(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Name cannot be empty");
        }
        String cleanName = name.trim();

        // Check for duplicate name in this room
        boolean duplicate = players.values().stream()
                .anyMatch(p -> p.getName().equalsIgnoreCase(cleanName));
        if (duplicate) {
            throw new IllegalArgumentException("Name already taken in this room");
        }

        String playerId = UUID.randomUUID().toString();
        players.put(playerId, new Player(playerId, cleanName));

        // Notify host of the updated player count
        broadcastPlayerCount();

        return playerId;
    }

    /**
     * Attaches the host's SSE emitter stream.
     */
    public synchronized void setHostEmitter(SseEmitter emitter) {
        this.hostEmitter = emitter;
        emitter.onCompletion(() -> this.hostEmitter = null);
        emitter.onTimeout(() -> this.hostEmitter = null);
        emitter.onError(e -> this.hostEmitter = null);

        // Immediately send current player count
        broadcastPlayerCount();
    }

    /**
     * Sends the current player count to the host screen.
     */
    public void broadcastPlayerCount() {
        SseEmitter emitter = this.hostEmitter;
        if (emitter != null) {
            try {
                // Rule 6: Host sees player COUNT only, no player names or scores
                emitter.send(SseEmitter.event()
                        .name("player_count")
                        .data(Map.of("count", players.size())));
            } catch (Exception e) {
                this.hostEmitter = null;
            }
        }
    }

    /**
     * Sends a comment heartbeat every 15s so proxies/mobile connections don't drop.
     */
    private void sendHeartbeat() {
        SseEmitter emitter = this.hostEmitter;
        if (emitter != null) {
            try {
                emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (Exception e) {
                this.hostEmitter = null;
            }
        }
    }

    /**
     * Shuts down the background scheduler when room is destroyed.
     */
    public void destroy() {
        scheduler.shutdownNow();
    }
}
