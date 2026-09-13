# ADR 0002: H2 in PostgreSQL mode for tests and the demo profile, PostgreSQL in CI and production

Status: accepted (2026-09-12)

## Context

Production runs on PostgreSQL. The test suite must be fast and run on a laptop without Docker; the
demo profile must run from `docker run` with no external services. Hibernate validates the entity
mapping against the Flyway schema on every start.

## Options

1. PostgreSQL everywhere through Testcontainers. Honest, but every test needs Docker and a
   container start; the demo profile would still need a database.
2. H2 everywhere. Fast, but the dialect differences (identity columns, `TEXT` as CLOB, no
   expression indexes) go unnoticed until production.
3. H2 in PostgreSQL mode (`MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH`) for
   unit, repository and web tests and for the demo profile, plus Testcontainers PostgreSQL tests
   for the migrations and the concurrency race that run whenever Docker is present and are
   mandatory in GitHub Actions (`-Dinnkeeper.requireDocker=true` turns a skip into a failure).

## Decision

Option 3. The schema is written in the portable subset both engines accept (identity columns,
`TIMESTAMP WITH TIME ZONE`, `NUMERIC`, `VARCHAR(n)` never `TEXT`, `CHECK` constraints, no partial or
expression indexes) and was run on both engines before the first entity was written.

## Consequences

- Repository slice tests must keep `@AutoConfigureTestDatabase(replace = NONE)` or Spring Boot swaps
  in a plain-mode H2.
- Emails are lower-cased in code (with a `CHECK` constraint) instead of an expression index.
- The PostgreSQL tests are skipped locally without Docker with the message
  "Docker is not available; skipping PostgreSQL tests", and never skipped in CI.
