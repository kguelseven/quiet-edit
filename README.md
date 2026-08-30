# quietedit

Detects silent edits to news articles: ingest RSS feeds, fetch full text, version
every fassung, classify what changed.

## Requirements

- Java 25
- Maven 3.9.12
- Docker (for the local database and for Testcontainers-backed tests)

## Running locally

### 1. Start the database

```bash
docker compose up -d
```

PostgreSQL 18.6 listens on **localhost:55432** (non-default port, so it does not
collide with a Postgres already running on 5432). Data lives in the named volume
`quietedit-pgdata` and therefore survives `docker compose down` and restarts.

Stop it with `docker compose stop`, or remove the container with
`docker compose down`. To also discard the data:
`docker compose down -v`.

### 2. Run the application

```bash
mvn spring-boot:run
```

The app starts on http://localhost:8080 and runs Flyway against the database on
startup.

### Configuration

All settings have local defaults and are overridable via environment variables:

| Variable                 | Default                                          |
|--------------------------|--------------------------------------------------|
| `QUIETEDIT_DB_URL`       | `jdbc:postgresql://localhost:55432/quietedit`     |
| `QUIETEDIT_DB_USER`      | `quietedit`                                      |
| `QUIETEDIT_DB_PASSWORD`  | `quietedit`                                      |
| `QUIETEDIT_DB_NAME`      | `quietedit` (docker compose only)                 |
| `QUIETEDIT_PORT`         | `8080`                                           |

## Tests

```bash
mvn verify
```

Tests run under the `test` profile and start their **own** throwaway PostgreSQL
container via Testcontainers. They never touch the docker-compose database, so
`docker compose up` is not a prerequisite — only a running Docker daemon is.
