package com.hitesh.quiz;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/**
 * Manages all game logic, server-side timer, scoring, and SSE streams for a single quiz room.
 */
public class GameRoom {

    /** Question duration in seconds — easy to change in this ONE constant. */
    public static final int QUESTION_DURATION_SECONDS = 20;

    /** Room code displayed on the projector (e.g. "ABCD"). */
    private final String roomCode;

    /** Secret token required for host operations. */
    private final String hostToken;

    /** The list of questions loaded from questions.json. */
    private final List<Question> questions;

    /** JSON serializer for SSE events to guarantee reliable formatting. */
    private final ObjectMapper objectMapper;

    /** Current question index (0 to questions.size() - 1). */
    private volatile int currentQuestionIndex = -1;

    /** Epoch timestamp in ms when the current question countdown expires. */
    private volatile long questionEndsAt = 0;

    /** Registered players keyed by playerId. */
    private final Map<String, Player> players = new ConcurrentHashMap<>();

    /** Active SSE emitter for the host screen. */
    private volatile SseEmitter hostEmitter;

    /** Active SSE emitters for each player's phone keyed by playerId. */
    private final Map<String, SseEmitter> playerEmitters = new ConcurrentHashMap<>();

    /** Background scheduler for question timer and 15s heartbeats. */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    /** Active countdown future. */
    private ScheduledFuture<?> currentTimerFuture;

    /** Room lifecycle states. */
    public enum State { LOBBY, QUESTION, RESULTS, FINISHED }
    private volatile State state = State.LOBBY;

    /**
     * Inner model for a player participating in the quiz.
     */
    public static class Player {
        private final String id;
        private final String name;
        private int score = 0;
        private volatile Integer currentAnswer = null; // 0-3 option index
        private volatile long answeredAt = 0; // ms epoch
        private volatile int lastPointsEarned = 0;
        private volatile boolean lastCorrect = false;

        public Player(String id, String name) {
            this.id = id;
            this.name = name;
        }

        public String getId() { return id; }
        public String getName() { return name; }
        public int getScore() { return score; }
        public Integer getCurrentAnswer() { return currentAnswer; }
        public long getAnsweredAt() { return answeredAt; }
        public int getLastPointsEarned() { return lastPointsEarned; }
        public boolean isLastCorrect() { return lastCorrect; }

        public void submitAnswer(int answerIndex, long now) {
            this.currentAnswer = answerIndex;
            this.answeredAt = now;
        }

        public void resetForNextQuestion() {
            this.currentAnswer = null;
            this.answeredAt = 0;
        }

        public void recordResult(int points, boolean correct) {
            this.lastPointsEarned = points;
            this.lastCorrect = correct;
            if (correct) {
                this.score += points;
            }
        }
    }

    /** Leaderboard item for host screen. */
    public record LeaderboardEntry(int rank, String name, int score) {}

    public GameRoom(String roomCode, List<Question> questions, ObjectMapper objectMapper) {
        this.roomCode = roomCode;
        this.questions = questions;
        this.objectMapper = objectMapper;
        this.hostToken = UUID.randomUUID().toString();

        // Send ~15s heartbeat comment to keep mobile connections alive
        scheduler.scheduleAtFixedRate(this::sendHeartbeats, 15, 15, TimeUnit.SECONDS);
    }

    public String getRoomCode() { return roomCode; }
    public String getHostToken() { return hostToken; }
    public State getState() { return state; }
    public int getPlayerCount() { return players.size(); }
    public Player getPlayer(String playerId) { return players.get(playerId); }
    public int getCurrentQuestionIndex() { return currentQuestionIndex; }
    public long getQuestionEndsAt() { return questionEndsAt; }
    public List<Question> getQuestions() { return questions; }

    /** Returns current question or null if in lobby or finished. */
    public Question getCurrentQuestion() {
        if (currentQuestionIndex >= 0 && currentQuestionIndex < questions.size()) {
            return questions.get(currentQuestionIndex);
        }
        return null;
    }

    /**
     * Registers a new player into the room.
     * Rejects duplicate names (case-insensitive) and empty names.
     */
    public synchronized String addPlayer(String name) {
        if (state != State.LOBBY) {
            throw new IllegalStateException("Quiz has already started");
        }
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Name cannot be empty");
        }
        String cleanName = name.trim();
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
        emitter.onCompletion(() -> {
            if (this.hostEmitter == emitter) this.hostEmitter = null;
        });
        emitter.onTimeout(() -> {
            if (this.hostEmitter == emitter) this.hostEmitter = null;
        });
        emitter.onError(e -> {
            if (this.hostEmitter == emitter) this.hostEmitter = null;
        });

        // Send current player count on connection
        broadcastPlayerCount();
    }

    /**
     * Attaches a player's phone SSE emitter stream.
     */
    public void setPlayerEmitter(String playerId, SseEmitter emitter) {
        playerEmitters.put(playerId, emitter);
        emitter.onCompletion(() -> playerEmitters.remove(playerId, emitter));
        emitter.onTimeout(() -> playerEmitters.remove(playerId, emitter));
        emitter.onError(e -> playerEmitters.remove(playerId, emitter));
    }

    /**
     * Starts the quiz on question 0.
     * Returns the Question object started.
     */
    public synchronized Question startQuiz() {
        if (questions.isEmpty()) {
            throw new IllegalStateException("No questions available");
        }
        if (state != State.LOBBY) {
            throw new IllegalStateException("Quiz has already started");
        }
        return startQuestion(0);
    }

    /**
     * Advances to the next question or finishes the quiz.
     */
    public synchronized Question nextQuestion() {
        if (state != State.RESULTS) {
            throw new IllegalStateException("Cannot advance question right now");
        }
        int nextIndex = currentQuestionIndex + 1;
        if (nextIndex < questions.size()) {
            return startQuestion(nextIndex);
        } else {
            finishQuiz();
            return null;
        }
    }

    /**
     * Starts a specific question and schedules the server-side countdown timer.
     */
    private synchronized Question startQuestion(int index) {
        this.state = State.QUESTION;
        this.currentQuestionIndex = index;
        for (Player p : players.values()) {
            p.resetForNextQuestion();
        }

        long now = System.currentTimeMillis();
        this.questionEndsAt = now + (QUESTION_DURATION_SECONDS * 1000L);

        Question q = questions.get(index);

        // 1. Notify host screen
        sendToHost("question_started", Map.of(
                "questionNumber", index + 1,
                "totalQuestions", questions.size(),
                "text", q.text(),
                "options", q.options(),
                "serverNow", now,
                "endsAt", questionEndsAt,
                "durationSeconds", QUESTION_DURATION_SECONDS
        ));

        // 2. Notify all player phones with safe DTO (STRICT RULE 1: no correctIndex)
        Question.PlayerQuestionDto playerDto = q.toPlayerDto(
                index + 1, questions.size(), now, questionEndsAt
        );
        for (Map.Entry<String, SseEmitter> entry : playerEmitters.entrySet()) {
            sendSse(entry.getValue(), "question_started", playerDto);
        }

        // 3. Schedule the server-side timer to close the question exactly after 20s
        if (currentTimerFuture != null && !currentTimerFuture.isDone()) {
            currentTimerFuture.cancel(false);
        }
        currentTimerFuture = scheduler.schedule(this::closeQuestion, QUESTION_DURATION_SECONDS, TimeUnit.SECONDS);

        return q;
    }

    /**
     * Records a player's answer submission.
     */
    public synchronized void submitAnswer(String playerId, int answerIndex) {
        if (state != State.QUESTION) {
            throw new IllegalStateException("No active question to answer");
        }
        long now = System.currentTimeMillis();
        if (now > questionEndsAt) {
            throw new IllegalStateException("Time is up! Answer rejected.");
        }
        Player player = players.get(playerId);
        if (player == null) {
            throw new IllegalArgumentException("Player not found");
        }
        if (player.getCurrentAnswer() != null) {
            throw new IllegalStateException("You already answered this question");
        }
        if (answerIndex < 0 || answerIndex > 3) {
            throw new IllegalArgumentException("Invalid option index");
        }

        player.submitAnswer(answerIndex, now);

        // Notify host of updated answer count
        long answeredCount = players.values().stream().filter(p -> p.getCurrentAnswer() != null).count();
        sendToHost("answer_count", Map.of(
                "answered", answeredCount,
                "total", players.size()
        ));
    }

    /**
     * Closes the active question, calculates points and ranks, and pushes results.
     */
    private synchronized void closeQuestion() {
        if (state != State.QUESTION) {
            return;
        }
        this.state = State.RESULTS;
        Question q = questions.get(currentQuestionIndex);
        long durationMs = QUESTION_DURATION_SECONDS * 1000L;

        // Calculate scores for each player
        for (Player p : players.values()) {
            if (p.getCurrentAnswer() != null && p.getCurrentAnswer() == q.correctIndex()) {
                long timeLeft = Math.max(0, questionEndsAt - p.getAnsweredAt());
                int speedBonus = (int) Math.round(500.0 * timeLeft / durationMs);
                int points = 500 + speedBonus; // 500 base + up to 500 bonus
                p.recordResult(points, true);
            } else {
                p.recordResult(0, false);
            }
        }

        // Compute rankings
        List<Player> sorted = new ArrayList<>(players.values());
        sorted.sort((a, b) -> Integer.compare(b.getScore(), a.getScore()));

        Map<String, Integer> playerRanks = new HashMap<>();
        for (int i = 0; i < sorted.size(); i++) {
            playerRanks.put(sorted.get(i).getId(), i + 1);
        }

        // Host leaderboard (top 5)
        List<LeaderboardEntry> top5 = getTopLeaderboard(5);
        boolean isLast = (currentQuestionIndex + 1 >= questions.size());

        // 1. Send host question results + top 5 leaderboard
        sendToHost("question_closed", Map.of(
                "correctIndex", q.correctIndex(),
                "sourceUrl", q.sourceUrl() != null ? q.sourceUrl() : "",
                "leaderboard", top5,
                "isLastQuestion", isLast,
                "questionNumber", currentQuestionIndex + 1
        ));

        // 2. Send each player phone their own result only (STRICT RULE 6)
        for (Map.Entry<String, SseEmitter> entry : playerEmitters.entrySet()) {
            String pId = entry.getKey();
            Player p = players.get(pId);
            if (p != null) {
                int rank = playerRanks.getOrDefault(pId, sorted.size());
                sendSse(entry.getValue(), "question_closed", Map.of(
                        "correct", p.isLastCorrect(),
                        "correctIndex", q.correctIndex(),
                        "pointsEarned", p.getLastPointsEarned(),
                        "yourScore", p.getScore(),
                        "yourRank", rank,
                        "totalPlayers", sorted.size(),
                        "isLastQuestion", isLast
                ));
            }
        }
    }

    /**
     * Finishes the quiz after the last question.
     */
    private synchronized void finishQuiz() {
        this.state = State.FINISHED;

        List<LeaderboardEntry> fullLeaderboard = getTopLeaderboard(players.size());
        sendToHost("quiz_finished", Map.of(
                "leaderboard", fullLeaderboard
        ));

        // Each player gets their final rank and final score
        List<Player> sorted = new ArrayList<>(players.values());
        sorted.sort((a, b) -> Integer.compare(b.getScore(), a.getScore()));
        for (int i = 0; i < sorted.size(); i++) {
            Player p = sorted.get(i);
            SseEmitter emitter = playerEmitters.get(p.getId());
            if (emitter != null) {
                sendSse(emitter, "quiz_finished", Map.of(
                        "yourRank", i + 1,
                        "yourScore", p.getScore(),
                        "totalPlayers", sorted.size()
                ));
            }
        }
    }

    public List<LeaderboardEntry> getTopLeaderboard(int limit) {
        List<Player> sorted = new ArrayList<>(players.values());
        sorted.sort((a, b) -> Integer.compare(b.getScore(), a.getScore()));
        List<LeaderboardEntry> res = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, sorted.size()); i++) {
            Player p = sorted.get(i);
            res.add(new LeaderboardEntry(i + 1, p.getName(), p.getScore()));
        }
        return res;
    }

    public int getPlayerRank(String playerId) {
        List<Player> sorted = new ArrayList<>(players.values());
        sorted.sort((a, b) -> Integer.compare(b.getScore(), a.getScore()));
        for (int i = 0; i < sorted.size(); i++) {
            if (sorted.get(i).getId().equals(playerId)) {
                return i + 1;
            }
        }
        return sorted.size();
    }

    /** Sends player count event to host. */
    public void broadcastPlayerCount() {
        sendToHost("player_count", Map.of("count", players.size()));
    }

    /** Sends a named event to the host screen. */
    public void sendToHost(String eventName, Object data) {
        SseEmitter emitter = this.hostEmitter;
        if (emitter != null) {
            sendSse(emitter, eventName, data);
        }
    }

    /** Safe helper to send pre-serialized JSON SSE event. */
    private void sendSse(SseEmitter emitter, String eventName, Object data) {
        if (emitter == null) return;
        try {
            String json = (data instanceof String s) ? s : objectMapper.writeValueAsString(data);
            emitter.send(SseEmitter.event().name(eventName).data(json));
        } catch (Exception e) {
            // Emitter dead or connection reset
        }
    }

    /**
     * Sends ~15s SSE comment heartbeats to keep mobile and host connections alive.
     */
    private void sendHeartbeats() {
        SseEmitter host = this.hostEmitter;
        if (host != null) {
            try { host.send(SseEmitter.event().comment("heartbeat")); } catch (Exception ignored) {}
        }
        for (SseEmitter pe : playerEmitters.values()) {
            try { pe.send(SseEmitter.event().comment("heartbeat")); } catch (Exception ignored) {}
        }
    }

    public void destroy() {
        scheduler.shutdownNow();
    }
}
