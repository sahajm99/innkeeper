# Innkeeper

[![ci](https://github.com/sahajm99/innkeeper/actions/workflows/ci.yml/badge.svg)](https://github.com/sahajm99/innkeeper/actions/workflows/ci.yml)

Innkeeper started as the CSCE 5350 Fundamentals of Database Systems group project at UNT
(Fall 2024, Group 15); the code, schema and application here are a new implementation.

A hotel group with three Texas branches: a public booking site where anyone can check dates, book a
room, print the invoice, cancel and file a complaint without signing up, and a staff desk behind a
demo login for check-in, check-out, payments, fines, maintenance, inventory and employees. Spring
Boot 3.5 on Java 21, PostgreSQL in production, H2 for tests and the demo profile, a JSON API with
OpenAPI, tests that gate CI, a Docker image, and a demo database that resets itself every night.

**Live demo:** not deployed yet (hosting is the last phase; see the hosting layout below). Run it
locally with one Docker command, below. The data is fictional and resets to the seed every night at
03:00 Central (and on every restart of the demo profile).

## Try it in 30 seconds

1. Pick dates on the landing page and search rooms; open a room to see its 60-day availability
   strip; book it. The confirmation page shows a code such as `INN-7K3Q9P`. Keep the code and
   your email: together they open the booking again under **Find my booking**.
2. Print the invoice, then cancel the booking. Cancelling more than 48 hours before 3 pm on the
   check-in day is free; later it records one night's rate on the invoice (nothing is charged in
   the demo).
3. Press **Run the race** on the landing page: ten concurrent bookings for one room and one night,
   one created, nine rejected by the database's unique constraint.
4. Sign in as staff (`staff / staff123`) or as a manager (`manager / manager123`, who also sees
   employees and can reset the demo data). The desk lists today's departures and arrivals with
   check-out and check-in buttons, occupancy per branch, open maintenance and low stock. A guest
   demo login also exists (`guest / guest123`) and lists that account's bookings.
5. The API is at `/api/docs` (Swagger UI over the OpenAPI document at `/api/openapi`): create a booking with
   `POST /api/bookings`, open it with `POST /api/bookings/lookup`, cancel it with
   `POST /api/bookings/{code}/cancel`.

## The interesting part

The course project checked availability by room *type* and then marked the first room of that type
occupied, so two guests could hold one room. Innkeeper keeps a `room_night` table with one row per
(room, night) held by a booking and `UNIQUE (room_id, night_date)`. Booking creation inserts the
nights inside one transaction; a concurrent loser hits the constraint, its transaction rolls back,
and the service translates the violation into HTTP 409 at the transaction boundary (a `REQUIRES_NEW`
`TransactionTemplate` in a non-transactional facade, because the violation surfaces at commit and
PostgreSQL aborts the transaction on the first error). Two tests fire eight threads at one room:
`BookingRaceTest` on H2 and `PostgresBookingRaceTest` on PostgreSQL in Testcontainers. The
landing page runs the same race live. The reasoning is in [docs/adr](docs/adr).

## Architecture

```
 browser / curl / OpenAPI UI
        |
   Thymeleaf pages (web/)          JSON API (api/, ProblemDetail errors)
        |                                   |
   services (booking, availability, invoice, staff desk, maintenance, inventory,
             complaints, employees, reset, race demo)  <- explicit transaction boundaries
        |
   domain (pure Java, tested without Spring): StayPeriod, OccupancyRule, InvoiceCalculator,
          CancellationPolicy, CheckInOutPolicy, ConfirmationCodes, BranchDates
        |
   Spring Data JPA over a Flyway-managed schema (V1 schema, V2 seed as a Java migration)
        |
   H2 in PostgreSQL mode (tests, demo profile)  |  PostgreSQL (prod, Testcontainers in CI)

 filters: request id + structured request log, per-IP rate limit
 security: stateless API chain; form login with GUEST / STAFF / MANAGER for pages
 schedules: in-app nightly reset (03:00 Central) + GitHub Actions cron that wakes the instance
```

## Run it locally

With Docker (embedded H2 file database, no external services):

```bash
docker run --rm -p 8080:8080 ghcr.io/sahajm99/innkeeper:latest
```

From source (Java 21 and Maven):

```bash
mvn -q spring-boot:run
```

Both start the `demo` profile on http://localhost:8080 with the seed data and the demo logins
printed on the login page. The database file lives under `./data` (ignored by git).

## Profiles and configuration

| Profile | Database | Use |
|---|---|---|
| `demo` (default) | H2 file `./data/innkeeper` in PostgreSQL mode | `docker run`, local development |
| `prod` | PostgreSQL from `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`; refuses to start without them | Render + Neon |
| `test` | H2 in memory, PostgreSQL mode | the test suite; Testcontainers overrides it for the PostgreSQL tests |

| Variable | Meaning |
|---|---|
| `SPRING_PROFILES_ACTIVE` | `demo` or `prod` |
| `DATABASE_URL` | `jdbc:postgresql://host/db?sslmode=require` (prod) |
| `DATABASE_USERNAME`, `DATABASE_PASSWORD` | database credentials (prod) |
| `INNKEEPER_RESET_TOKEN` | required by `POST /internal/reset`; the endpoint answers 404 when unset |

See [.env.example](.env.example). Secrets live in Render environment variables and GitHub Actions
secrets, never in the repository.

## Tests

```bash
mvn -q -B verify                                  # everything; PostgreSQL tests skip without Docker
mvn -q -B verify -Dinnkeeper.requireDocker=true   # what CI runs: the PostgreSQL tests are mandatory
```

346 tests: 50 domain unit tests (no Spring), 35 repository tests on H2 in PostgreSQL mode with Flyway
applied and Hibernate `validate` on, service tests including the 8-thread race, MockMvc tests for
every page and API endpoint (validation errors, 302/403 on staff pages, CSRF, rate limit,
honeypot, reset token), and PostgreSQL Testcontainers tests for the migrations and the race.

## Hosting layout

| What | Where | Notes |
|---|---|---|
| Web service | Render free web service built from the `Dockerfile` (`render.yaml`) | 0.1 CPU, 512 MB; sleeps after 15 idle minutes; the image ships an AppCDS archive and small-heap JVM flags for the cold start |
| Database | Neon PostgreSQL (`prod` profile) when a connection string is configured; otherwise the `demo` profile's embedded H2 file, which is ephemeral on the free tier | the schema was verified on both engines |
| Deploys | GitHub Actions `ci.yml`: build, tests, Docker image (pushed to GHCR on `main`), then a Render deploy through the API followed by a smoke check that books and cancels through the API | badge above |
| Nightly reset | `nightly-reset.yml` at 09:30 UTC wakes the instance and calls `POST /internal/reset`; the in-app schedule at 03:00 Central covers an instance that is awake; a manager can also press "Reset demo data" | one transaction: delete child-first, reseed, stamp `last_reset_at` |
| Keep-warm | `keep-warm.yml` pings health every 10 minutes, 13:00 to 05:00 UTC | outside those hours expect a cold start |

GitHub pauses scheduled workflows after 60 days without repository activity; Dependabot opens
monthly PRs to keep it active, and if the schedules do pause, the Actions tab shows an "Enable
workflow" button.

## What changed versus the course design

Real `DATE` and `TIMESTAMP WITH TIME ZONE` columns instead of varchar dates, foreign keys with
explicit `ON DELETE` rules, `NOT NULL` and `CHECK` constraints, BCrypt password hashes, no identity
numbers or card numbers anywhere, a room-night ledger against double booking, an invoice record with
a snapshotted tax rate, and an append-only booking audit. The table-by-table diff is in
[docs/SCHEMA-CHANGES.md](docs/SCHEMA-CHANGES.md); the design is in [docs/DESIGN.md](docs/DESIGN.md);
the contested choices are in [docs/adr](docs/adr); the review record and decision log are in
[docs/REVIEW.md](docs/REVIEW.md) and [docs/DECISIONS.md](docs/DECISIONS.md).

## Screenshots

The screenshot set at 1280 and 400 px in light and dark is the next item on the plan
(`docs/screenshots`); until then, run it locally: the landing page, the room register and the
staff desk are the pages to look at first.

## Licence

MIT, see [LICENSE](LICENSE). Fraunces and Source Sans 3 are bundled under the SIL Open Font
License (see `src/main/resources/static/fonts`).
