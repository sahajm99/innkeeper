# ADR 0001: A room-night ledger row is the double-booking guard

Status: accepted (2026-09-12)

## Context

The course project checked availability with a query by room *type* and then set the first room
of that type to "Occupied", so two bookings could hold the same room. Innkeeper needs a guard that
holds under concurrent requests, on PostgreSQL in production and on H2 in tests and the demo
profile.

## Options

1. An overlap query before insert (`SELECT ... WHERE room_id = ? AND check_in < ? AND check_out > ?`).
   Races: two requests can both see "free" and both insert.
2. A PostgreSQL exclusion constraint on `booking`:
   `EXCLUDE USING gist (room_id WITH =, daterange(check_in_date, check_out_date) WITH &&)`.
   One constraint, no extra table, but PostgreSQL-only (needs `btree_gist`); H2 cannot run it, so
   tests and the demo profile would run without the guard.
3. A `room_night` table with one row per (room, night) held by a booking and
   `UNIQUE (room_id, night_date)`. Portable, and the availability strip is a plain query over it.

## Decision

Option 3. Booking creation inserts one `room_night` row per night inside one transaction; a
concurrent loser hits the unique constraint, the transaction rolls back and the service translates
the violation into `RoomUnavailableException` (HTTP 409). The translation happens at the transaction
boundary (a non-transactional facade around a `REQUIRES_NEW` `TransactionTemplate`), because the
exception surfaces at flush or commit and PostgreSQL aborts the transaction on the first error.

## Consequences

- Up to 30 extra rows per booking (the maximum stay); trivial at this scale.
- The guard is exercised by `BookingRaceTest` (H2) and `PostgresBookingRaceTest` (Testcontainers)
  and demonstrated live by the "Run the race" button.
- Cancellation and early check-out must delete their rows, or the strip lies; both do, under test.
