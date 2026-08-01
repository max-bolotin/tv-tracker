# TV Tracker

A monolithic Spring Boot + React app for tracking TV show viewing progress.

## Prerequisites
- Java 25+
- Maven 3.9+
- Node.js 22+ (only needed for standalone frontend dev; Maven downloads its own Node for builds)

## Build & Run

### Full production build (React + Spring Boot JAR)
```bash
mvn clean package
java -jar target/tv-tracker-0.0.1-SNAPSHOT.jar
```
Then open http://localhost:8080

### Development mode (hot-reload)
Terminal 1 — Spring Boot backend:
```bash
mvn spring-boot:run
```
Terminal 2 — Vite dev server (proxies /api to :8080):
```bash
cd frontend
npm install
npm run dev
```
Then open http://localhost:5173

## Configuration

| Property | Default | Description |
|---|---|---|
| `app.storage.path` | `./data/tv-tracker-data.json` | JSON data file location |
| `app.tmdb.api-key` | *(empty)* | TMDB API key — set via env var `TMDB_API_KEY` |
| `app.scheduler.cron` | `0 0 3 * * *` | Daily update check schedule |

If `TMDB_API_KEY` is not set, all metadata calls fall back to TVMaze (free, no key needed).

## API Endpoints

| Method | Path | Description |
|---|---|---|
| GET | `/api/shows` | List all tracked shows (optional `?status=` filter) |
| GET | `/api/shows/{id}` | Get single show |
| POST | `/api/shows` | Add show `{ tmdbId, tvmazeId }` |
| DELETE | `/api/shows/{id}` | Remove show |
| PATCH | `/api/shows/{id}/status` | Update watch status `{ status }` |
| PATCH | `/api/shows/{id}/seasons/{s}/episodes/{e}` | Toggle episode `{ watched }` |
| PATCH | `/api/shows/{id}/seasons/{s}` | Toggle entire season `{ watched }` |
| GET | `/api/shows/search?q=` | Search external APIs |
| GET | `/api/data/export` | Download JSON backup |
| POST | `/api/data/import` | Upload JSON backup (multipart) |
