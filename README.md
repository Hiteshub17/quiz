# 🎯 Live Classroom Quiz Web App (Mini Kahoot)

A lightweight, high-performance live classroom quiz web app built with **Java 21**, **Spring Boot 4.1.2**, and **Vanilla HTML/CSS/JS**. All state is kept in-memory with zero external database dependencies.

---

## 🚀 Quick Start

### 1. Run Locally with Maven Wrapper
```powershell
# From the project root
.\mvnw.cmd clean package -DskipTests
java -jar target\quiz-0.0.1-SNAPSHOT.jar
```
The server will start at: `http://localhost:8080`

### 2. Run with Docker
```bash
docker build -t quiz-app .
docker run -p 8080:8080 quiz-app
```

### 3. Deploy to Render
The project includes a multi-stage `Dockerfile` and `application.properties` configured for `${PORT:8080}`:
- **Build Stage**: `maven:3.9-eclipse-temurin-21`
- **Runtime Stage**: `eclipse-temurin:21-jre`
- Health check path: `/health` (returns plain text `"ok"`)

---

## 📱 How to Play

1. **Host Screen**: Open [`http://localhost:8080/host.html`](http://localhost:8080/host.html)
   - Generates a random 4-character room code (e.g. `XU5T`) displayed **BIG** for a classroom projector.
   - Shows live count of joined players.
   - Host clicks **Start Quiz** to begin.

2. **Player Screen**: Open [`http://localhost:8080/play.html`](http://localhost:8080/play.html) on phones/laptops
   - Enter Room Code + Nickname.
   - Join lobby and wait for host to start.

3. **Question & Scoring Flow**:
   - 5 questions loaded from `src/main/resources/questions.json`.
   - Authoritative **20-second server countdown** timer per question (`ScheduledExecutorService`).
   - 4 large Kahoot-style color buttons (Red ▲, Blue ◆, Yellow ●, Green ■).
   - Once submitted, the answer is locked in.
   - Scoring:
     - Wrong or missed: `0` points.
     - Correct: `500` base points + up to `500` speed bonus (`500 * timeLeft / durationMs`).
   - Results:
     - Host screen reveals correct answer with source reference link, then shows Top 5 Leaderboard.
     - Player screen displays right/wrong, points earned, their own total score, and their rank (#X of Y).

---

## 📂 Codebase Overview (Small & Simple)

The codebase is deliberately kept minimal so every file is easy to explain:

| File | Purpose |
|------|---------|
| [`QuizApplication.java`](src/main/java/com/hitesh/quiz/QuizApplication.java) | Spring Boot 4 entry point. |
| [`Question.java`](src/main/java/com/hitesh/quiz/Question.java) | Question record loaded from JSON, plus a separate safe `PlayerQuestionDto` (without answers). |
| [`GameRoom.java`](src/main/java/com/hitesh/quiz/GameRoom.java) | All in-memory state (`ConcurrentHashMap`), player registration, 20s server timer, scoring formula, and SSE broadcasting. |
| [`QuizController.java`](src/main/java/com/hitesh/quiz/QuizController.java) | REST API endpoints for room creation, joining, starting/advancing questions, submitting answers, state restoration, and SSE streams. |
| [`questions.json`](src/main/resources/questions.json) | 5 personal finance questions. Adding a 6th question only requires adding one JSON object. |
| [`host.html`](src/main/resources/static/host.html) | Projector screen: big room code, player counter, question reveal, and Top 5 leaderboard. |
| [`play.html`](src/main/resources/static/play.html) | Mobile player screen: join form, tap-to-answer buttons, personalized results, and auto-state restore on refresh. |

---

## 🛡️ Security & Strict Rules Review

| Rule # | Requirement | Implementation & Status |
|:---:|:---|:---|
| **1** | `correctIndex` must NEVER be sent before question closes | ✅ **PASS** — Players receive only `PlayerQuestionDto` during question countdown. `correctIndex` is only sent inside `question_closed` event after the timer expires. |
| **2** | 10+ concurrent players on mobile data & small SSE payloads | ✅ **PASS** — SSE payloads use compact JSON strings. A background scheduler pushes `:heartbeat\n\n` comments every 15s to prevent mobile drops. |
| **3** | Refresh / disconnect survivability | ✅ **PASS** — `GET /api/rooms/{code}/state` restores full state (question, time left, score, rank) on page reload without stacking UI views. |
| **4** | No frozen screens with error / retry UI | ✅ **PASS** — All `fetch` calls use 6-second `AbortController` timeouts. Errors display friendly banners with explicit Retry buttons. Reconnecting banner displays on SSE drops. |
| **5** | Zero secrets in code & config | ✅ **PASS** — `hostToken` is generated randomly at runtime (`UUID.randomUUID()`) and kept in memory. `.gitignore` excludes `target/`, `.idea/`, `.env`, and `*.log`. |
| **6** | Scores & player lists are strictly private | ✅ **PASS** — Room code alone exposes zero names or scores. Host leaderboard requires `X-Host-Token`. Player endpoint requires valid `playerId` and returns only their own score/rank. No player names or scores are logged. |

---

## 📡 API Reference

### Public Endpoints
- `GET /health` → Returns `"ok"`
- `POST /api/rooms` → Creates room, returns `{ roomCode, hostToken }`
- `POST /api/rooms/{code}/join` → Body `{"name":"..."}` → Returns `{ playerId, roomCode }`
- `POST /api/rooms/{code}/answer` → Body `{"playerId":"...","answerIndex":0..3}`

### Host Endpoints (Require `X-Host-Token` or `?token=`)
- `POST /api/rooms/{code}/start` → Starts quiz on Question 1
- `POST /api/rooms/{code}/next` → Advances to next question or final results
- `GET /api/rooms/{code}/events/host` → Live SSE stream (player count, question start, answer count, results)

### State & Sync Endpoints
- `GET /api/rooms/{code}/state?playerId=...` → Returns player's personal phase, active question (no answer), score, rank
- `GET /api/rooms/{code}/state?token=...` → Returns host phase, player count, active question, leaderboard
- `GET /api/rooms/{code}/events/player?playerId=...` → Live player SSE stream
