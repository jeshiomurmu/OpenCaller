# CLAUDE.md — OpenCaller agent entry point

You are working on OpenCaller, an open-source Android caller ID / spam blocker / dialer / SMS app with a Kotlin (Ktor) backend.

## Read before every task

@docs/rules.md
@docs/phase.md

Read these when the task touches them:

- Product scope, MVP, non-goals: `docs/prd.md`
- Modules, schemas, APIs, app flow: `docs/architecture.md`
- Colors, typography, components: `docs/design.md`
- Test cases, QA steps, bug log: `docs/testing.md`
- Phase checklists: `docs/implementation_plan.md`

## The five rules that matter most

1. **Work only on the current phase** in `docs/phase.md`. If a task belongs to a later phase, stop and say so.
2. **Never invent APIs, library versions or Gradle coordinates.** Look them up (official docs, Maven Central, Google Maven) and record the version in `android/gradle/libs.versions.toml`. If you cannot verify, stop and ask.
3. **Never upload a user's contacts, call log or SMS bodies to any server.** See "Privacy invariants" in `docs/rules.md`.
4. **Every change ships with tests and a green build** (`./gradlew check` in `android/`, `./gradlew test` in `backend/server/`).
5. **Update `docs/phase.md`** at the end of every task: what changed, what's next, open issues.
