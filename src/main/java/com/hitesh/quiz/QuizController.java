package com.hitesh.quiz;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles all HTTP endpoints for rooms, player joining, and host SSE streams.
 */
@RestController
@CrossOrigin
public class QuizController {

    /** In-memory store of active quiz rooms keyed by uppercase roomCode. */
    private final Map<String, GameRoom> rooms = new ConcurrentHashMap<>();

    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // readable chars without I, 1, O, 0
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Simple health check — returns plain text "ok". */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("ok");
    }

    /**
     * Creates a new quiz room with a random 4-character code and a host token.
     * Host token is stored in memory and returned only to the creator.
     */
    @PostMapping("/api/rooms")
    public ResponseEntity<Map<String, String>> createRoom() {
        String code = generateUniqueRoomCode();
        GameRoom room = new GameRoom(code);
        rooms.put(code, room);

        return ResponseEntity.ok(Map.of(
                "roomCode", room.getRoomCode(),
                "hostToken", room.getHostToken()
        ));
    }

    /**
     * DTO for player join request.
     */
    public record JoinRequest(String name) {}

    /**
     * Allows a player to join an existing room in the LOBBY phase.
     * Rejects duplicate names and empty names.
     */
    @PostMapping("/api/rooms/{code}/join")
    public ResponseEntity<?> joinRoom(@PathVariable String code, @RequestBody JoinRequest req) {
        GameRoom room = rooms.get(code.toUpperCase());
        if (room == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Room not found. Check the code and try again."));
        }

        if (room.getState() != GameRoom.State.LOBBY) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "Quiz has already started. You cannot join now."));
        }

        try {
            String playerId = room.addPlayer(req.name());
            return ResponseEntity.ok(Map.of(
                    "playerId", playerId,
                    "roomCode", room.getRoomCode()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Server-Sent Events stream for the host screen.
     * Protected by the host token (via X-Host-Token header or ?token= query parameter).
     */
    @GetMapping(value = "/api/rooms/{code}/events/host", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> hostEvents(
            @PathVariable String code,
            @RequestHeader(value = "X-Host-Token", required = false) String headerToken,
            @RequestParam(value = "token", required = false) String queryToken) {

        GameRoom room = rooms.get(code.toUpperCase());
        if (room == null) {
            return ResponseEntity.notFound().build();
        }

        String token = (headerToken != null && !headerToken.isBlank()) ? headerToken : queryToken;
        if (token == null || !token.equals(room.getHostToken())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // Long-lived SSE emitter (e.g. 30 minutes)
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
        room.setHostEmitter(emitter);

        return ResponseEntity.ok(emitter);
    }

    /** Helper method to get active room (used internally). */
    public GameRoom getRoom(String code) {
        return rooms.get(code.toUpperCase());
    }

    /** Generates a short, user-friendly 4-character room code. */
    private String generateUniqueRoomCode() {
        String code;
        do {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                sb.append(CODE_CHARS.charAt(RANDOM.nextInt(CODE_CHARS.length())));
            }
            code = sb.toString();
        } while (rooms.containsKey(code));
        return code;
    }
}
