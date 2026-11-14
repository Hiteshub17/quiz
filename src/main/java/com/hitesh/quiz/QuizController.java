package com.hitesh.quiz;

import jakarta.annotation.PostConstruct;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles all HTTP endpoints for rooms, players, state restoration, and SSE streams.
 */
@RestController
@CrossOrigin
public class QuizController {

    private final ObjectMapper objectMapper;

    /** In-memory store of active quiz rooms keyed by uppercase roomCode. */
    private final Map<String, GameRoom> rooms = new ConcurrentHashMap<>();

    /** Questions loaded from questions.json at startup. */
    private List<Question> questions = new ArrayList<>();

    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    public QuizController(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Loads the questions from src/main/resources/questions.json.
     * Adding a 6th question only requires adding an object to the JSON file.
     */
    @PostConstruct
    public void loadQuestions() {
        try (InputStream is = getClass().getResourceAsStream("/questions.json")) {
            if (is == null) {
                throw new IllegalStateException("questions.json not found in classpath");
            }
            Question[] arr = objectMapper.readValue(is, Question[].class);
            this.questions = List.of(arr);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load questions.json: " + e.getMessage(), e);
        }
    }

    /** Simple health check — returns plain text "ok". */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("ok");
    }

    /**
     * Creates a new quiz room with a random 4-character code and a host token.
     */
    @PostMapping("/api/rooms")
    public ResponseEntity<Map<String, String>> createRoom() {
        String code = generateUniqueRoomCode();
        GameRoom room = new GameRoom(code, questions, objectMapper);
        rooms.put(code, room);

        return ResponseEntity.ok(Map.of(
                "roomCode", room.getRoomCode(),
                "hostToken", room.getHostToken()
        ));
    }

    public record JoinRequest(String name) {}

    /**
     * Allows a player to join an existing room in the LOBBY phase.
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
     * Reconnect/State endpoint (Strict Rule 3).
     * Survives page refreshes for both host and players.
     */
    @GetMapping("/api/rooms/{code}/state")
    public ResponseEntity<?> getRoomState(
            @PathVariable String code,
            @RequestParam(value = "playerId", required = false) String playerId,
            @RequestHeader(value = "X-Host-Token", required = false) String headerToken,
            @RequestParam(value = "token", required = false) String queryToken) {

        GameRoom room = rooms.get(code.toUpperCase());
        if (room == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Room not found"));
        }

        long now = System.currentTimeMillis();
        long timeLeft = Math.max(0, room.getQuestionEndsAt() - now);

        // 1. Host State
        if (isHostAuthorized(room, headerToken, queryToken)) {
            Question q = room.getCurrentQuestion();
            return ResponseEntity.ok(Map.of(
                    "role", "host",
                    "phase", room.getState().name(),
                    "playerCount", room.getPlayerCount(),
                    "questionNumber", room.getCurrentQuestionIndex() + 1,
                    "totalQuestions", room.getQuestions().size(),
                    "question", q != null ? q : Map.of(),
                    "timeLeft", timeLeft,
                    "endsAt", room.getQuestionEndsAt(),
                    "serverNow", now,
                    "leaderboard", room.getTopLeaderboard(5)
            ));
        }

        // 2. Player State (Rule 6: Never expose others' scores or correct answer)
        if (playerId != null && !playerId.isBlank()) {
            GameRoom.Player player = room.getPlayer(playerId);
            if (player == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Player not in room"));
            }

            Question q = room.getCurrentQuestion();
            Object questionDto = (q != null && room.getState() == GameRoom.State.QUESTION)
                    ? q.toPlayerDto(room.getCurrentQuestionIndex() + 1, room.getQuestions().size(), now, room.getQuestionEndsAt())
                    : null;

            Map<String, Object> stateMap = new java.util.LinkedHashMap<>();
            stateMap.put("role", "player");
            stateMap.put("phase", room.getState().name());
            stateMap.put("question", questionDto != null ? questionDto : Map.of());
            stateMap.put("timeLeft", timeLeft);
            stateMap.put("yourScore", player.getScore());
            stateMap.put("yourRank", room.getPlayerRank(playerId));
            stateMap.put("totalPlayers", room.getPlayerCount());
            stateMap.put("hasAnswered", player.getCurrentAnswer() != null);
            stateMap.put("selectedAnswer", player.getCurrentAnswer() != null ? player.getCurrentAnswer() : -1);
            stateMap.put("lastCorrect", player.isLastCorrect());
            stateMap.put("lastPointsEarned", player.getLastPointsEarned());

            return ResponseEntity.ok(stateMap);
        }

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Authorization required"));
    }

    /**
     * Starts the quiz. Requires valid host token.
     */
    @PostMapping("/api/rooms/{code}/start")
    public ResponseEntity<?> startQuiz(
            @PathVariable String code,
            @RequestHeader(value = "X-Host-Token", required = false) String headerToken,
            @RequestParam(value = "token", required = false) String queryToken) {

        GameRoom room = rooms.get(code.toUpperCase());
        if (room == null) return ResponseEntity.notFound().build();

        if (!isHostAuthorized(room, headerToken, queryToken)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            Question q = room.startQuiz();
            long now = System.currentTimeMillis();
            return ResponseEntity.ok(Map.of(
                    "status", "started",
                    "questionNumber", 1,
                    "totalQuestions", room.getQuestions().size(),
                    "text", q.text(),
                    "options", q.options(),
                    "serverNow", now,
                    "endsAt", room.getQuestionEndsAt(),
                    "durationSeconds", GameRoom.QUESTION_DURATION_SECONDS
            ));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Advances to the next question or finishes the quiz. Requires valid host token.
     */
    @PostMapping("/api/rooms/{code}/next")
    public ResponseEntity<?> nextQuestion(
            @PathVariable String code,
            @RequestHeader(value = "X-Host-Token", required = false) String headerToken,
            @RequestParam(value = "token", required = false) String queryToken) {

        GameRoom room = rooms.get(code.toUpperCase());
        if (room == null) return ResponseEntity.notFound().build();

        if (!isHostAuthorized(room, headerToken, queryToken)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            Question q = room.nextQuestion();
            long now = System.currentTimeMillis();
            if (q != null) {
                return ResponseEntity.ok(Map.of(
                        "status", "question",
                        "questionNumber", room.getCurrentQuestionIndex() + 1,
                        "totalQuestions", room.getQuestions().size(),
                        "text", q.text(),
                        "options", q.options(),
                        "serverNow", now,
                        "endsAt", room.getQuestionEndsAt(),
                        "durationSeconds", GameRoom.QUESTION_DURATION_SECONDS
                ));
            } else {
                return ResponseEntity.ok(Map.of("status", "finished"));
            }
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
        }
    }

    public record AnswerRequest(String playerId, int answerIndex) {}

    /**
     * Player submits their answer to the current question.
     */
    @PostMapping("/api/rooms/{code}/answer")
    public ResponseEntity<?> submitAnswer(@PathVariable String code, @RequestBody AnswerRequest req) {
        GameRoom room = rooms.get(code.toUpperCase());
        if (room == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Room not found"));
        }

        try {
            room.submitAnswer(req.playerId(), req.answerIndex());
            return ResponseEntity.ok(Map.of("status", "submitted"));
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Server-Sent Events stream for the host screen.
     * Returns direct SseEmitter to avoid container entity wrapping issues.
     */
    @GetMapping(value = "/api/rooms/{code}/events/host", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter hostEvents(
            @PathVariable String code,
            @RequestHeader(value = "X-Host-Token", required = false) String headerToken,
            @RequestParam(value = "token", required = false) String queryToken) {

        GameRoom room = rooms.get(code.toUpperCase());
        if (room == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found");
        }

        if (!isHostAuthorized(room, headerToken, queryToken)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthorized host");
        }

        SseEmitter emitter = new SseEmitter(0L); // 0L = no timeout
        room.setHostEmitter(emitter);
        return emitter;
    }

    /**
     * Server-Sent Events stream for a player's phone.
     */
    @GetMapping(value = "/api/rooms/{code}/events/player", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter playerEvents(
            @PathVariable String code,
            @RequestParam("playerId") String playerId) {

        GameRoom room = rooms.get(code.toUpperCase());
        if (room == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found");
        }

        GameRoom.Player player = room.getPlayer(playerId);
        if (player == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Player not found in room");
        }

        SseEmitter emitter = new SseEmitter(0L);
        room.setPlayerEmitter(playerId, emitter);
        return emitter;
    }

    private boolean isHostAuthorized(GameRoom room, String headerToken, String queryToken) {
        String token = (headerToken != null && !headerToken.isBlank()) ? headerToken : queryToken;
        return token != null && token.equals(room.getHostToken());
    }

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
