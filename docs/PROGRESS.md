# Progress

One entry per milestone with how it was verified.

## 0. Design (2026-09-12)

- Read the course dump, ER diagrams, proposal and Swing sources; wrote `DESIGN.md` and
  `SCHEMA-CHANGES.md`. Verified by re-reading the dump's fifteen `CREATE TABLE` statements
  against the schema-changes table (every course table has a row).
- Four independent reviews (spec, CEO, design, engineering) recorded in `REVIEW.md`; every
  finding decided and folded into `DESIGN.md`, `SCHEMA-CHANGES.md` and `DECISIONS.md`.
- `V1__schema.sql` executed on H2 2.3 in PostgreSQL mode (`org.h2.tools.RunScript`) and on a
  `postgres:16-alpine` container (`psql -v ON_ERROR_STOP=1`): 19 tables on both, and a duplicate
  `(room_id, night_date)` insert fails with the `uq_room_night` violation on both.
- Implementation plan written to `superpowers/plans/2026-09-12-innkeeper.md` (16 tasks).
- Public repo `sahajm99/innkeeper` created and the docs commit pushed.

## (a) Schema, migrations, seed data, domain tests (2026-09-12)

- Tasks 1-4 of the plan: Maven project with `demo` / `prod` / `test` profiles, `V1__schema.sql`
  under Hibernate `validate`, the pure domain core (stay, occupancy, invoice, cancellation,
  check-in/out policies, confirmation codes), 19 JPA entities and repositories, and the seed data as
  a Java migration (`V2__seed_data`) with bookings relative to the seed day.
- Verified: `mvn -q -B test` runs 110 tests green (50 domain unit tests, 35 repository tests on H2
  in PostgreSQL mode, 15 seed tests, 4 PostgreSQL Testcontainers tests against `postgres:16-alpine`
  that confirm the migrations and identity sequences on the real engine); each task had a
  fresh-context code review with fixes applied (`.superpowers` ledger, not committed).
