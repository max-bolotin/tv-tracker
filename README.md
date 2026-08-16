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
| `app.storage.path` | `./data/users/` | Per-user JSON files directory (files named `<userId>.json`) |
| `app.tmdb.api-key` | *(empty)* | TMDB API key — set via env var `TMDB_API_KEY` |
| `app.scheduler.cron` | `0 0 3 * * *` | Daily update check schedule |

If `TMDB_API_KEY` is not set, metadata calls fall back to TVMaze where possible.

Upload limits and configuration

- Default upload limits are controlled by environment variables (suitable for Docker/OCI):
  - UPLOAD_MAX_FILE_SIZE (e.g. "50MB") — maps to spring.servlet.multipart.max-file-size
  - UPLOAD_MAX_REQUEST_SIZE (e.g. "50MB") — maps to spring.servlet.multipart.max-request-size
  - MAX_SWALLOW_SIZE (bytes, e.g. 52428800) — maps to server.tomcat.max-swallow-size

Docker example (override limits at runtime):

```bash
docker run -e UPLOAD_MAX_FILE_SIZE=100MB -e UPLOAD_MAX_REQUEST_SIZE=100MB -e MAX_SWALLOW_SIZE=104857600 -p 8080:8080 tv-tracker
```

Docker Compose snippet:

```yaml
services:
  tv-tracker:
    image: tv-tracker:latest
    ports: ['8080:8080']
    environment:
      - UPLOAD_MAX_FILE_SIZE=100MB
      - UPLOAD_MAX_REQUEST_SIZE=100MB
      - MAX_SWALLOW_SIZE=104857600
```

OCI (Compute / Container Instances): set the same environment variables in the container configuration or in your deployment manifest. For OCI Container Engine (OKE), include them in the Pod/Deployment env section.

API Endpoints

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
| GET | `/api/shows/popular?limit={n}` | Get up to `n` popular/trending shows from TMDB (unauthenticated) |
| GET | `/api/data/export` | Download JSON backup |
| POST | `/api/data/import` | Upload JSON backup (multipart) |

Notes
- The app stores per-user data in `./data/users/{userId}.json`. For legacy single-file installs the app still supports a single JSON file (automatic migration planned).
- To customize upload limits in production, set the environment variables shown above — do not edit the packaged JAR.
- Frontend file-size validation uses Vite env var `VITE_MAX_UPLOAD_BYTES` (bytes). Set this at build time if you want the client to enforce a different limit, e.g.: `VITE_MAX_UPLOAD_BYTES=104857600 npm run build`.
