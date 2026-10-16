# ADR 0002 — Kotlin + Ktor backend

- Status: Accepted
- Date: 2026-10-15
- Reviewed: 2026-10-16 (Phase 0): Kotlin + Ktor decision unchanged; remains Accepted. Note: MinIO upstream no longer publishes container images and its repository is archived, so the local stack now runs Chainguard's free MinIO build; the long-term object-storage choice needs a follow-up decision before Phase 4 (see `docs/phase.md` → Open issues)

## Context

The research document suggested Go or Rust for low-latency lookups. The team is small and the app is Kotlin.

## Decision

Use Kotlin + Ktor for the API server, PostgreSQL + Exposed + Flyway, Redis, MinIO. Python is used only for ML training and LiveKit Agents (AI assistant, recording, voice scanner).

## Consequences

- One language for app and server; shared models possible via a small `shared` JVM module if needed.
- Revisit if bucket lookup p95 server time exceeds 100 ms at production load; the endpoint is isolated so it can be rewritten alone.
