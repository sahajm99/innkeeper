# ADR 0003: Nightly reset by transactional delete-and-reseed, triggered from outside

Status: accepted (2026-09-12)

## Context

The public demo lets anyone book, cancel, file complaints and log in as staff. The data must return
to the seed every night. The instance runs on Render's free tier, which sleeps after fifteen
minutes without traffic, so an in-process scheduler alone cannot be relied on.

## Options

1. Flyway `clean()` + `migrate()` at runtime. Drops every table under live Hikari connections and
   Hibernate sessions, needs `spring.flyway.clean-disabled=false` in production, and gives in-flight
   requests errors during the window.
2. A `TRUNCATE ... CASCADE` + reseed. Atomic on PostgreSQL, but H2 has no multi-table truncate.
3. One transaction that deletes every table child-first and reseeds through the same `SeedData`
   class the V2 migration uses. Portable, atomic on both engines (readers see the old rows or the
   new rows), no DDL, testable in the suite.

## Decision

Option 3, guarded by a `ReentrantLock.tryLock()` (a concurrent call gets 409) and by a
"stale" check (a second call within 30 minutes is a no-op unless forced). Three callers share it:
`POST /internal/reset` with the `X-Reset-Token` header, called by a GitHub Actions cron at
09:30 UTC that first polls the health endpoint so a sleeping instance wakes up; an in-app
`@Scheduled` job at 03:00 America/Chicago for the case the instance is awake; and the manager's
"Reset demo data" button.

## Consequences

- Identity values keep growing across resets; nothing depends on specific ids.
- Sessions that unlocked a booking code before the reset get a redirect to the lookup page, not an
  error.
- GitHub pauses scheduled workflows after 60 days without repository activity; Dependabot keeps the
  repository active and the README explains how to re-enable the schedules.
- With the demo profile on Render, the disk is ephemeral, so every restart also resets the data.
