# quietedit

Detects silent edits to news articles: ingest RSS feeds, fetch full text, version every fassung, classify what changed.

## Stack

- Java 25, Spring Boot 4.1.1, Maven 3.9.12, PostgreSQL 18.6, Spring Data JPA / Hibernate / Flyway (comes from the Spring Boot BOM). 
- Tests: JUnit 5, Testcontainers, WireMock, AssertJ.
- Libraries: rome (feeds), jsoup (HTML parsing), java-diff-utils (diffing).
- No further dependencies unless named in the ticket.
- Pin exact versions in the parent POM; do not upgrade them inside a ticket.

## Architecture
- Package root: org.korhan
- Base package: `org.korhan.quietedit`. One package per feature area (`ingest`, `versioning`, `analysis`), not per layer.
- Layering: Controller → Service → Repository. Business logic lives in the service layer only. Controllers stay thin. Repositories contain no logic.

Domain terms:
- **Feed** — a subscribed RSS or Atom source
- **Document** — one article, identified by its canonical URL
- **DocumentVersion** — one observed revision of a document, immutable,
  append-only
- **Change** — a detected difference between two document versions

Scheduling: scheduled jobs contain no logic. They call a synchronous `runOnce()` method that is also reachable via REST and testable without a running scheduler.

## Conventions

- The schema from the foundation ticket may be extended, but only when the ticket says so, and only additively: new fields and tables are fine, renaming or removing existing ones is not.
- Records for DTOs, API request/response types and value objects. Entities stay mutable classes (JPA requirement).
- Constructor injection. No field injection. No Lombok.
- The Flyway version number is stated in the ticket. Do not invent one.
- JPA for persistence. Queries via derived method names or @Query with JPQL; native SQL only where a Postgres-specific feature requires it (jsonb operators, similarity search).
- Error responses use Problem Details (RFC 7807), never stack traces.
- `mvn verify` must pass before a ticket is closed.
- Javadoc concise and compact only where the reasoning is not obvious from the code: why this approach, which trade-off, which edge case. No Javadoc that restates the signature.
- Where a ticket asks for a documented justification (thresholds, chosen heuristics, known weaknesses), it belongs in the Javadoc of the class that implements it -- not only in the close reason.
- Do not commit `.beads/`. Its contents are handled separately.

## Testing

Test core logic only. If a class or method holds no core logic, it gets no test.

- Core logic here means: boilerplate extraction, content hashing, URL canonicalisation, diffing, change classification, re-check policy. Fixture-driven unit tests, fixtures under `src/test/resources/`.
- Integration tests only for the full ingest run and the version store's append-only guarantee. Testcontainers for the database, WireMock for outbound HTTP.
- Goldsets and determinism requirements named in a ticket are part of its acceptance criteria.
- No tests for getters, mappers, repository defaults, controller wiring or Spring configuration.


## Session protocol

One session handles exactly **one ticket**.

1. `git pull --rebase` — start from the current state
2. `bd ready -t task` — take the top ticket. Do not invent your own ordering. Epics are containers, never work items; do not claim one.
3. `bd update <id> --claim`, then read `bd show <id>` in full — especially the acceptance criteria, the scope boundary and the Flyway line
4. Implement, including tests
5. Work discovered along the way that does not belong to this ticket becomes its own ticket: `bd create "..." --deps discovered-from:<id>` Do not silently fix it. Do not ignore it either.
6. `mvn verify`
7. `bd close <id> --reason "..."` — what was built, which decision was made, what remains open. "Done" is not a usable reason.
8. Create branch `feat/<id>`, commit there, push the branch and open a PR with `gh pr create`. Committing, pushing a feature branch and opening a PR are explicitly authorised by this protocol. **Never push to main. Never merge.**
9. Hand off: PR link, changed files, test results, ticket status, any new tickets created.

The Beads block below applies in addition. Where the two conflict, this section wins.

<!-- BEGIN BEADS INTEGRATION v:1 profile:minimal hash:6cd5cc61 -->
## Beads Issue Tracker

This project uses **bd (beads)** for issue tracking. Run `bd prime` to see full workflow context and commands.

### Quick Reference

```bash
bd ready              # Find available work
bd show <id>          # View issue details
bd update <id> --claim  # Claim work
bd close <id>         # Complete work
```

### Rules

- Use `bd` for ALL task tracking — do NOT use TodoWrite, TaskCreate, or markdown TODO lists
- Run `bd prime` for detailed command reference and session close protocol
- Use `bd remember` for persistent knowledge — do NOT use MEMORY.md files

**Architecture in one line:** issues live in a local Dolt DB; sync uses `refs/dolt/data` on your git remote; `.beads/issues.jsonl` is a passive export. See https://github.com/gastownhall/beads/blob/main/docs/SYNC_CONCEPTS.md for details and anti-patterns.

### Agent Context Profiles

The managed Beads block is task-tracking guidance, not permission to override repository, user, or orchestrator instructions.

- **Conservative (default)**: Use `bd` for task tracking. Do not run git commits, git pushes, or Dolt remote sync unless explicitly asked. At handoff, report changed files, validation, and suggested next commands.
- **Minimal**: Keep tool instruction files as pointers to `bd prime`; use the same conservative git policy unless active instructions say otherwise.
- **Team-maintainer**: Only when the repository explicitly opts in, agents may close beads, run quality gates, commit, and push as part of session close. A current "do not commit" or "do not push" instruction still wins.

### Session Completion

This protocol applies when ending a Beads implementation workflow. It is subordinate to explicit user, repository, and orchestrator instructions.

1. **File issues for remaining work** - Create beads for anything that needs follow-up
2. **Run quality gates** (if code changed) - Tests, linters, builds
3. **Update issue status** - Close finished work, update in-progress items
4. **Handle git/sync by active profile**:
   ```bash
   # Conservative/minimal/default: report status and proposed commands; wait for approval.
   git status

   # Team-maintainer opt-in only, unless current instructions forbid it:
   git pull --rebase
   git push
   git status
   ```
5. **Hand off** - Summarize changes, validation, issue status, and any blocked sync/commit/push step

**Critical rules:**
- Explicit user or orchestrator instructions override this Beads block.
- Do not commit or push without clear authority from the active profile or the current user request.
- If a required sync or push is blocked, stop and report the exact command and error.
<!-- END BEADS INTEGRATION -->
