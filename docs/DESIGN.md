# Innkeeper design

Innkeeper is a hosted hotel-group management application: a public booking site for a small
group of hotels plus a staff desk for arrivals, departures, maintenance and inventory. It is a
from-scratch rewrite of the CSCE 5350 Fundamentals of Database Systems group project (UNT,
Fall 2024, Group 15), which was a Java Swing desktop app over MySQL with raw JDBC. The course
dump and ER diagram were used only as the domain specification; no course code is reused.

This document is the specification the implementation plan argues from. It was revised after
four independent reviews; the rulings are in `REVIEW.md` and the one-line reasons in
`DECISIONS.md`.

## 1. What the course project did (domain specification)

Read from `FinalDumpHotelManagementSystem.sql`, `ER_Diagram.png`, `Hotel_management_Database.png`,
the phase-1 proposal and the 25 Swing sources.

| Course table | What it held | What we learned about the domain |
|---|---|---|
| `branch` | name, location, phone, `branch_inventory_id` | The group runs several branches (TX, RH, AZ). |
| `room` | `RoomID`, `RoomType` (Standard/Deluxe/Luxury), `Status` | Rooms have a type; the type sets the nightly rate (30/50/60 in `CheckOut.calculatePrice`). |
| `room_booking` | phone, name, email, type, `Check_in`, `Check_out`, `roomid`, `customer_id` | A booking is a guest, a room and a date range. Overlap check was by room *type*, not room, so double booking was possible. |
| `booking_history` | copy of a booking made at check-out, dates as varchar | Check-out archives the stay. |
| `customer` | `ID` (SSN or State ID), `number`, name, gender, contact, country, email | Guest identity. SSN-like numbers stored in plaintext. |
| `login` | username, plaintext password, role, customer_id, employee_id | Three roles: customer, employee, admin. |
| `employee` | name, DOB as varchar, gender, phone, ssn, email, salary, role_name, job, branch_id | Staff belong to a branch and have a job (Housekeeping, Porters, Room Service). |
| `role` | admin, employee | Role lookup. |
| `fine` | amount as varchar, date, customer_id | A flat $30 fine when the guest checks out after the check-out date. |
| `payment` | amount, type, `payment_details` (full card number in plaintext), date as varchar | Payment recorded at check-out. |
| `complain` | name, description, phone, customer_id | Guests file complaints. |
| `maintenance_team` | name, email | Cleaning, Appliances, Electric, Plumbing, House Keeping, gardening. |
| `maintenence_work` | type, description, scheduled_date as varchar, roomid, status (Pending/In Progress/Completed), team_id | Maintenance requests per room, assigned to a team. |
| `inventory` | item_name, quantity, branch_id | Per-branch stock (sheets, blankets, toilet paper). |
| `parking` | space number, label (Employee/Customer) | Parking spaces exist; nothing links them to anyone. |

Course flows: search rooms by type; book (customer info + dates, first room of the type is set
Occupied); check out (price = rate x days + $30 late fine, record payment, archive booking, set room
Available); complaints; maintenance requests with team assignment and status; inventory by branch;
add employee (creates a login with a shared password); branch management.

Observed facts recorded for honesty: the phase-1 proposal document is headed "Group 8" while the
final dump folder is `HMS_Group15_Master`; `READ_ME.pdf` in the course folder describes a different
Python assignment and was not used.

## 2. Domain model

Money is `NUMERIC(10,2)` / `BigDecimal`, rounded once per line with HALF_UP. Calendar days are
`LocalDate`; instants are `Instant` (columns `TIMESTAMP WITH TIME ZONE`). Every policy class takes a
`java.time.Clock`; nothing calls `now()` without it. "Today" always means today in the branch
timezone: `LocalDate.now(clock.withZone(branch.zone()))`.

- **Branch**: code, name, tagline, description, address, phone, email, tax rate (`NUMERIC(5,4)`),
  timezone. Owns rooms, inventory, employees, maintenance teams, parking spaces.
- **RoomType**: code (STANDARD, DELUXE, SUITE), name, description, max occupancy, bed setup.
- **Room**: branch + number (unique per branch), room type, floor, nightly rate, status
  (AVAILABLE / OUT_OF_SERVICE). Out of service rooms are never offered.
- **Guest**: first name, last name, email (stored lower-case, indexed, not unique: email is not a
  verified identity, so every booking gets its own guest row), phone.
- **Booking**: confirmation code, room, guest, check-in date, check-out date, adults, children,
  special requests, status (CONFIRMED / CHECKED_IN / CHECKED_OUT / CANCELLED), nightly rate
  snapshot, cancellation fee, `@Version`, created / checked-in / checked-out / cancelled instants.
- **RoomNight**: one row per (room, night) held by a booking. `UNIQUE (room_id, night_date)` is the
  double-booking guard and holds under concurrency because the database enforces it.
- **Invoice** + **InvoiceLine**: one invoice per booking; snapshots the tax rate; lines for room
  nights, tax, each fine, the cancellation fee; stored totals with a check constraint that
  `total = room_subtotal + tax + fines + cancellation_fee`. Rebuilt on every transition while the
  booking is open; immutable once the booking is CHECKED_OUT or CANCELLED (status PAID / OPEN / VOID).
- **Payment**: invoice, amount, method (CARD / CASH), reference, paid at, recorded by. No card data.
- **Fine**: booking, reason, amount, issued at, issued by. Untaxed. Not allowed after check-out.
- **Complaint**: branch, optional booking (only when code + email match), ticket number, guest name
  and email, category, description, status (OPEN / IN_PROGRESS / RESOLVED), resolution note.
- **Employee**: branch, name, email, position, hired on, active.
- **UserAccount**: username, BCrypt hash, role (GUEST / STAFF / MANAGER), display name, optional
  employee, enabled. A GUEST account's display name is its email; `/my-bookings` lists bookings for
  that email when a GUEST is logged in.
- **MaintenanceTeam**: branch, name, specialty, contact email.
- **MaintenanceRequest**: branch, optional room, title, description, priority (LOW / NORMAL /
  URGENT), status (OPEN / IN_PROGRESS / DONE), team, reported by, created / started / completed.
- **InventoryItem**: branch, name (unique per branch), category, quantity, reorder level, unit.
- **ParkingSpace**: branch, space number, kind (GUEST / STAFF), assigned booking (unique). Parking
  is complimentary; assignment is a conditional update (`WHERE booking_id IS NULL`).
- **BookingEvent**: append-only audit of transitions; actor is a username or "guest".
- **AppMetadata**: key/value rows (`seeded_at`, `last_reset_at`).

### Domain rules (each has a failing unit test before its code)

1. A stay has at least one night: check-out is strictly after check-in.
2. A stay is at most 30 nights; check-in is not before today (branch timezone) and at most 365
   days ahead.
3. Guests (adults + children) must not exceed the room type's max occupancy; adults at least 1.
4. No two bookings hold the same room on the same night. Availability = room AVAILABLE and no
   `room_night` rows in `[check_in, check_out)`. Creation inserts the nights inside one
   transaction; a loser of a race gets `RoomUnavailableException`, translated at the transaction
   boundary from the `uq_room_night` violation.
5. Nights charged = `ChronoUnit.DAYS.between(checkIn, checkOut)`. Room subtotal = nights x nightly
   rate snapshot.
6. Tax = room subtotal x invoice tax rate, `setScale(2, HALF_UP)` once. Fines and the cancellation
   fee are untaxed. Total = subtotal + tax + fines + cancellation fee. Balance = total - payments.
7. Cancellation: allowed only while CONFIRMED. Deadline = 15:00 on the check-in date in the branch
   timezone minus 48 hours (a `Duration`, so DST changes do not shift it). At or before the
   deadline: free. After it: one night's rate as the cancellation fee (recorded on the invoice; no
   money is collected online). Cancelling releases every room night.
8. Check-in: only CONFIRMED bookings, on or after the check-in date and before the check-out date
   (branch today).
9. Check-out: only CHECKED_IN bookings. Booked dates never change. Early check-out (today before
   the check-out date) charges `nightsCharged = max(1, today - check_in_date)` nights and deletes
   the room nights from `check_in_date + nightsCharged` onward (`CheckOutOutcome.releaseNightsFrom`;
   a same-day check-out keeps and charges the first night). Late check-out (today after the check-out date) adds a
   late fine of one night's rate per extra night (date arithmetic only, no check-out time); the
   ledger is not rewritten for nights already gone and the staff page says so. Until then, a
   CHECKED_IN booking past its check-out date (an overstay) keeps the room out of availability and
   is listed under departures as overdue. Check-out and cancellation release the parking space.
10. Confirmation codes are `INN-` plus six characters from `ABCDEFGHJKLMNPQRSTUVWXYZ23456789`;
    unique; a collision regenerates (three attempts).
11. Fines can be added only while CONFIRMED or CHECKED_IN. Payments only after check-out, at most
    the balance; the invoice becomes PAID when the balance reaches zero.
12. Staff transitions use optimistic locking; a stale update surfaces as 409. A CONFIRMED booking
    whose check-in date has passed without a check-in is a no-show; it stays CONFIRMED until the
    nightly reset clears it (no automatic no-show handling).
13. Invoice status: OPEN while anything is owed, PAID when payments reach the total, VOID when a
    booking is cancelled free of charge. A cancellation fee stays OPEN and is never collected in
    the demo. Check-out does not require payment; staff record one payment, prefilled with the
    balance. Dashboard definitions: arrivals = CONFIRMED with check-in today; departures =
    CHECKED_IN with check-out on or before today; occupancy = CHECKED_IN bookings whose stay
    covers today divided by rooms in service.

State machine:
```
CONFIRMED --cancel--> CANCELLED (fee if after deadline; nights released)
CONFIRMED --check in (today in [in, out))--> CHECKED_IN
CHECKED_IN --check out--> CHECKED_OUT (early: nights >= today released; late: late fine)
CHECKED_IN --cancel--> refused (409)    CHECKED_OUT/CANCELLED --anything--> refused (409)
```

## 3. Schema

Flyway `V1__schema.sql` (tested on H2 2.3 in PostgreSQL mode and on PostgreSQL 16) and
`V2__seed_data` (a Java migration registered as a Spring bean so it shares the `SeedData` class
with the nightly reset and gets a `Clock`). Nineteen tables, snake_case, `BIGINT GENERATED BY
DEFAULT AS IDENTITY` keys (entities declare `GenerationType.IDENTITY`; the seed never inserts
explicit ids), `DATE` for days, `TIMESTAMP WITH TIME ZONE` for instants, explicit `ON DELETE` on
every foreign key, `CHECK` on every status and money column. Hibernate runs with
`ddl-auto=validate` in every profile. See `SCHEMA-CHANGES.md` for the diff against the course.

```
branch 1---* room *---1 room_type
branch 1---* employee 0..1---0..1 user_account   (demo accounts have no employee)
branch 1---* inventory_item
branch 1---* maintenance_team 0..1---* maintenance_request *---0..1 room
branch 1---* maintenance_request
branch 1---* parking_space 0..1---0..1 booking
guest 1---* booking *---1 room
booking 1---* room_night          UNIQUE (room_id, night_date)
booking 1---1 invoice 1---* invoice_line
invoice 1---* payment *---0..1 employee (recorded_by)
booking 1---* fine *---0..1 employee (issued_by)
booking 1---* booking_event
branch 1---* complaint *---0..1 booking
app_metadata (meta_key, meta_value, updated_at)
```

Seed data (obviously fictional; every email on `example.com`; no phone numbers that look real):
three Texas branches (Denton Square, Fort Worth Stockyards, Austin Lakeline), three room types,
about 30 rooms, six employees, teams, inventory with two items below reorder level, parking spaces,
open and resolved complaints, maintenance requests in every status, demo accounts
`guest / guest123` (email `guest@example.com`), `staff / staff123`, `manager / manager123`
(hashes precomputed), and bookings relative to seed day: departures today, arrivals today,
in-house guests, future bookings (some for `guest@example.com`), a cancelled one and past
checked-out ones with paid invoices. `app_metadata.seeded_at` records the seed instant.

## 4. Pages (Thymeleaf, server-rendered)

Shared chrome: a one-line demo strip above the header ("Demo hotel group with fictional data.
Resets nightly at 03:00 Central. Free-tier hosting: the first load can take up to a minute or
two.");
header with wordmark, "Rooms", "Find my booking", "Complaints", "About", theme toggle, and
"Staff sign in" (or the staff nav when signed in: Desk, Bookings, Maintenance, Inventory,
Complaints, Employees for managers, Sign out). Footer: an "under the hood" line (commit, database
vendor, last reset) linking to `/about`. Dates render as "Fri 3 Oct 2026". Dates and guests carry
through every link as `checkIn`, `checkOut`, `guests` query parameters.

| Route | Who | What the visitor sees, in order |
|---|---|---|
| `/` | public | H1, then the search: check-in, check-out (native date inputs with min/max), guests, branch defaulting to "Any branch", "Search rooms". Then the three branch cards (name, city, tagline, from-rate). Then the "Run the race" panel (button + result). |
| `/rooms` | public | Filters (branch, type, max rate, dates) as one form; results as ledger rows: branch, room number and type, sleeps, rate per night, total for the stay when dates are set, "View room". Sold out branch: one line. No results: "Nothing free at any branch for Fri 3 Oct to Mon 6 Oct. Try shorter dates or another branch." |
| `/rooms/{id}` | public | Name, type, rate per night; the 60-day availability strip drawn as weeks (rows of 7) with the requested dates pre-selected; running total ("3 nights, $267.00") and "Book this room"; description and details last. Requested dates blocked: "Not available 3 to 6 Oct. Next 3-night opening: 9 Oct." |
| `/book` | public | Summary (branch, room, dates, nights, subtotal, tax, total) above the fields on mobile and beside them on desktop; policy line ("Free cancellation until 3 pm, Wed 1 Oct. After that, one night ($89.00)."); guest fields (first name, last name, email, phone optional, adults, children, requests); honeypot; "Confirm booking, $198.36". Errors inline under fields plus a summary that receives focus. 409: signal banner "Room 204 was just taken for those dates" with a link to alternatives. |
| `/bookings/{code}` | session-unlocked, GUEST with matching email, or staff | The code as the hero (display size, copy button), "Keep this code and your email. You need both to view or cancel."; email echoed large; dates, room, branch, status chip; invoice summary with "View invoice"; "Cancel booking" as a destructive outline button at the bottom (only while CONFIRMED). |
| `/bookings/{code}/cancel` | same | GET: confirm step ("Cancelling now is free." or "Cancelling now adds a $89.00 fee to your invoice (within 48 hours of 3 pm, Fri 3 Oct)."), "Keep booking" / "Cancel booking". POST: cancels, redirects to the booking page with a flash line. |
| `/bookings/{code}/invoice` | same | Printable: branch letterhead, code, guest, dates, line table, totals, payments, balance due; print CSS forces the light palette and hides chrome. |
| `/my-bookings` | public | Code + email form. Match: unlock and redirect. No match: "We couldn't find a booking with that code and email." Logged-in GUEST: list of bookings for the account email. `?code=` prefills the code (used when a session expired). |
| `/complaints/new` | public | Branch, optional booking code + email (must match a booking or the field errors), name, email, category, description, honeypot; success page with the ticket number. |
| `/about` | public | Origin credit, what is new versus the course design (link to SCHEMA-CHANGES), commit, build time, database product and version, active profile, last reset, seed time, connection pool stats. |
| `/login` | public | Username, password, "Sign in"; demo credentials printed; "Log in as staff" and "Log in as manager" buttons that prefill and submit. Staff land on `/staff`. |
| `/staff` | STAFF, MANAGER | Branch switcher (staff default to their employee's branch; managers see all). "Departures today (3)" ledger then "Arrivals today (4)" ledger, each row with guest, room, nights, balance and an in-row Check out / Check in button. Below: occupancy per branch (occupied / in-service rooms, percent), open maintenance count by priority, low-stock items, open complaints. Empty states as one sentence each. Managers also get "Reset demo data". |
| `/staff/bookings` | STAFF, MANAGER | Search by code, name, email or date; results ledger; "No bookings match." |
| `/staff/bookings/{code}` | STAFF, MANAGER | Booking header with status chip; actions by state: Check in (parking space select defaulting to none, free spaces only), Add fine (reason, amount), Check out (shows final invoice; late nights noted), Record payment (amount prefilled with the balance, CARD/CASH, reference), "Report maintenance issue" (prefills the room); invoice and event history. Guest emails and phones are masked on staff pages (`a***@example.com`). |
| `/staff/maintenance` | STAFF, MANAGER | Three columns Open / In progress / Done (Done = last 7 days) on wide screens, stacked sections with counts under 720 px; cards show room, priority chip (URGENT in signal), title, team, age; per-card buttons Start / Done and a team select; "New request" form with a "Take the room out of service until done" checkbox (refused with a message while the room has future room nights; marking the request Done returns the room to service). Rooms out of service are listed with a "Return to service" button. |
| `/staff/inventory` | STAFF, MANAGER | Per-branch table (item, category, quantity, reorder level, unit) with low-stock rows highlighted; adjust quantity (+/- form); add item. |
| `/staff/complaints` | STAFF, MANAGER | Open and in-progress complaints with "Start" / "Resolve" (note required). |
| `/staff/employees` | MANAGER | Employees per branch: list, add (name, email, position, branch), deactivate. STAFF gets the 403 page. |
| `/error/403`, `404`, `500`, `429` | public | Designed pages that show the request id. |

Every mutation is POST + redirect + flash message. Every form has a CSRF token and server-side
validation messages; JavaScript is optional everywhere (the strip and the race button add
behaviour, the pages work without them).

## 5. REST API (`/api`, JSON, springdoc OpenAPI UI at `/api/docs`)

| Method | Path | Notes |
|---|---|---|
| GET | `/api/branches` | Branch list. |
| GET | `/api/rooms?branchId&type&maxRate&checkIn&checkOut&guests` | Rooms; with dates only available rooms (occupancy filtered by `guests`, overstayed rooms excluded). |
| GET | `/api/rooms/{id}` | Room detail. |
| GET | `/api/rooms/{id}/availability?from&days` | Per-night availability, `days` 1..90 (default 60), `from` defaults to branch today. |
| POST | `/api/bookings` | Create; 201 with the booking and invoice; 400 with field errors; 409 when the room is taken; 429 when rate limited. |
| POST | `/api/bookings/lookup` | Body `{code, email}`; 200 booking + invoice; 404 when the pair does not match (email checked before anything else, so the endpoint is not a code oracle). |
| POST | `/api/bookings/{code}/cancel` | Body `{email}`; 200 with the fee; 404 on mismatch; 409 when not cancellable. |
| POST | `/api/demo/race` | Runs 10 concurrent booking attempts for one far-future night in a demo room, cancels the winner, returns `{attempts, created, rejected, winnerCode, room, night}`. Rate limited. |

The API chain is stateless with CSRF off; springdoc documents only `/api/**`; the reset endpoint
is hidden. The request log never records query strings.

## 6. Security

Two `SecurityFilterChain`s: `/api/**` stateless, CSRF off, all permitted; everything else with
form login at `/login`, CSRF on, `SameSite=Lax` cookies, roles GUEST / STAFF / MANAGER from
`user_account` (BCrypt). `/staff/**` needs STAFF or MANAGER; `/staff/employees/**` and the reset
button need MANAGER. `POST /internal/reset` is CSRF-exempt and needs the `X-Reset-Token` header
compared with `MessageDigest.isEqual` to `INNKEEPER_RESET_TOKEN`; when the token is not configured
the endpoint answers 404. Actuator exposes only `health` (details never); probes enabled so
`/actuator/health/liveness` works without the database. springdoc serves the spec at
`/api/openapi` and the UI at `/api/docs`; the UI's assets live under `/swagger-ui/**`, which the
page chain permits, and a MockMvc test fetches the spec anonymously. The rate limiter is off in the
`test` profile except in its own test. `server.forward-headers-strategy=native`
so client IPs, `Secure` cookies and redirects are right behind Render's proxy. Headers: CSP
`default-src 'self'` (all scripts and styles are files, none inline), no `frame-ancestors`,
`Referrer-Policy: same-origin`. Stack traces never render. No H2 console. `robots.txt` disallows
`/staff/`, `/bookings/`, `/my-bookings`, `/api/`.

Abuse floor for a public demo: an in-memory per-IP limiter (20 booking and 20 complaint POSTs per
hour, keyed on the first `X-Forwarded-For` hop, entries expire after an hour, map bounded at
10,000) answering 429 with a designed page or JSON; a hidden honeypot field that returns a silent
success; validation on every input (email length 254, code pattern `INN-[A-Z2-9]{6}`, text
lengths, date parse errors as 400). The booking and complaint forms say "Demo: do not enter real
personal data. Everything is wiped nightly." Staff pages mask guest emails and phones.

## 7. Profiles, hosting, reset

| Profile | Database | Use |
|---|---|---|
| `demo` (default when none is set) | H2 file `./data/innkeeper` in PostgreSQL mode | `docker run`, local dev |
| `prod` | PostgreSQL from `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`; fails fast when unset | Render + Neon |
| `test` | H2 in-memory, PostgreSQL mode | Unit, repository and MockMvc tests; Testcontainers overrides it for the Postgres test |

Hosting: Render free web service built from the repo `Dockerfile`. Render's free tier gives
0.1 CPU and 512 MB, so the image is a layered jar on `eclipse-temurin:21-jre` with an AppCDS
archive made by a training run, `-XX:TieredStopAtLevel=1 -XX:+UseSerialGC -XX:MaxRAMPercentage=60
-Xss512k`, Tomcat capped at 20 threads, BCrypt hashes precomputed. Neon Postgres when a
connection string is available (JDBC URL `jdbc:postgresql://host/db?sslmode=require`; Hikari
`maximum-pool-size=3, minimum-idle=0, idle-timeout=30000, max-lifetime=240000,
initialization-fail-timeout=60000`, no keepalive so Neon can suspend), otherwise the `demo`
profile in the same container. Render's health check is `/actuator/health/liveness`.

Deploys: GitHub Actions runs build, tests and the Docker build on every push and pull request; on
`main` it also pushes the image to GHCR, triggers a Render deploy through the API, waits for
`live`, and runs a smoke check (health, create and cancel a booking through the API). A keep-warm
workflow pings health every 10 minutes between 13:00 and 05:00 UTC. Dependabot (Maven and
Actions, monthly) keeps the scheduled workflows from being disabled for inactivity.

Nightly reset: `ResetService.reset(force)` runs in one transaction: delete every table child-first,
reseed through `SeedData` (the same class the V2 migration calls), write `last_reset_at`. A
`ReentrantLock.tryLock()` guards it (busy answers 409); `resetIfStale` skips when the last reset
was under 30 minutes ago unless forced. Callers: `POST /internal/reset` (GitHub Actions cron at
09:30 UTC with `curl --retry 5 --retry-all-errors --max-time 180`, which also wakes a sleeping
instance), the in-app `@Scheduled(cron = "0 0 3 * * *", zone = "America/Chicago")`, and the
manager's "Reset demo data" button (forced). After a reset, an unlocked code that no longer
exists redirects to the lookup page with a message, never a 500.

## 8. Observability

`/actuator/health` public; `/about` shows git commit and build time (git-commit-id plugin and
build-info, both optional at runtime; the commit falls back to the `RENDER_GIT_COMMIT` or
`GIT_COMMIT` environment variable), database product and version from the connection metadata,
active profile, seed and last-reset instants, Hikari active/idle/total. A `RequestLoggingFilter`
assigns or echoes `X-Request-Id`, adds it to every response, puts request id, method, path
(without query string), status, duration, principal and client IP into the MDC and logs one INFO
line per request; static assets are skipped. The `prod` and `demo` profiles set Spring Boot's
`logging.structured.format.console=ecs`, so every log line, including the request line and its
MDC fields, is one JSON object; `test` and local runs keep plain text. Error pages print the
request id.

## 9. Test plan

- Unit (JUnit 5 + AssertJ, no Spring): `StayPeriod` (rules 1, 2, 5), `OccupancyRule` (3),
  `InvoiceCalculator` (6, including 8.25 percent on odd cents and untaxed fines),
  `CancellationPolicy` (7, at the deadline, one second after, across the 2026-11-01 DST change),
  `CheckInOutPolicy` (8, 9: early, on time, late), `ConfirmationCodes` (10, alphabet, retry),
  `BranchClock` (today in Chicago at 19:00 Chicago time when UTC is already tomorrow).
- Service on H2 (`@SpringBootTest`, test profile): create translates `uq_room_night` to
  `RoomUnavailableException` and leaves no partial rows; code collision retries; race test with a
  `CyclicBarrier` and 8 threads on one room and night yields exactly one booking; cancel, check-in,
  check-out, fines, payments, parking assignment race, `@Version` conflict, invoice rebuild,
  reset (idempotent, stale-skip, forced, concurrent 409).
- Repository (`@DataJpaTest` with `replace = NONE` so H2 keeps PostgreSQL mode, Flyway applied,
  validate on): unique constraints, cascade rules, availability query, seed counts, today's
  arrivals and departures exist after seeding.
- Web (`@SpringBootTest` + MockMvc): every page renders for the right role; anonymous on
  `/staff/**` redirects to login; STAFF on `/staff/employees` gets 403; CSRF missing on a form POST
  gets 403; validation errors render; booking flow end to end through the pages including session
  unlock and lookup; complaint linking requires code + email; rate limit and honeypot; reset
  endpoint with missing, wrong and right token; every API endpoint including 400 / 404 / 409 / 429;
  `/about` renders without `git.properties`; error pages show the request id.
- PostgreSQL (Testcontainers `postgres:16`, `@ServiceConnection`): the context starts with
  `validate`, V1 + V2 apply, the race test passes, reset works. Skipped with the message "Docker is
  not available; skipping PostgreSQL tests" locally; in CI `-Dinnkeeper.requireDocker=true` turns a
  skip into a failure.
- Screenshot pass at 1280 and 400 px in light and dark with the headless browser, console checked
  for errors on every page.

## 10. Visual design

Subject: a small Texas hotel group with three branches, the kind that still prints its own room
keys. Warm and precise, distinct from an admin template; one memorable element, the registry-style
availability strip, and quiet discipline everywhere else.

Tokens (contrast ratios checked, WCAG AA at 4.5:1 for text and 3:1 for UI):

| Token | Light | Dark | Use |
|---|---|---|---|
| paper / ground | `#F7F3EC` | `#17140F` | page background |
| ink / text | `#1F1A17` (15.6:1) | `#EDE6DA` (14.8:1) | body text |
| walnut | `#5B3A29` (9.1:1) | `#C9A56B` brass (7.9:1) | headings accents, primary buttons, focus ring |
| brass | `#B08D57` decorative only (2.8:1 fails for text) | `#C9A56B` | rules on ledgers, day markers, dark-mode links |
| brass-text | `#7A5C30` (5.6:1) | `#C9A56B` | small brass text in light mode |
| pine | `#2F5D50` (6.8:1) | `#6FA795` (6.7:1) | success, available nights, status chips |
| signal | `#A4442E` (5.5:1) | `#E07A5F` (6.2:1) | errors, urgent, destructive |
| line | `#A89C8C` (3.0:1) | `#5A5044` | input borders, table rules |
| mist | `#E8E1D6` | `#241F18` | panels, zebra rows |

Type: Fraunces (variable, optical sizes) for display and headings; Source Sans 3 for everything
else; both self-hosted as latin woff2 under `static/fonts` with OFL licences. Scale 1.25 from
16 px; body measure under 72 characters; serif line-height 1.35, sans 1.5.

Layout: left-aligned; reading pages on a 64 rem measure, staff pages full width to 88 rem. Ledgers
(rooms, arrivals, invoice lines) use ruled rows; nothing gets a drop shadow; cards are panels on
mist with no border-radius larger than 4 px. Buttons: primary walnut on paper (brass on charcoal),
secondary outline, destructive outline in signal. Status chips: CONFIRMED pine outline,
CHECKED_IN pine solid, CHECKED_OUT ink outline, CANCELLED signal outline. Focus ring: 2 px walnut
(light) or brass (dark) with a 2 px paper offset. Tables stack into labelled rows under 640 px; the
invoice table scrolls horizontally instead. Theme follows `prefers-color-scheme` with a toggle in
the header stored in `localStorage` and applied by a tiny head script before first paint.
Motion only answers user actions (strip selection, flash line entrance); `prefers-reduced-motion`
removes it.

Availability strip: server-rendered as a grid of weeks, each cell a `<button>` with `data-date`,
`aria-label="Fri 3 Oct, available"` or "booked", `aria-pressed` for the selection, roving tabindex
(arrows move, Home/End jump, Enter sets check-in then check-out); booked nights are hatched, not
colour-only; a live region announces the selected range and total. Without JavaScript the cells
are links to `/book?roomId&checkIn&checkOut` for a one-night stay.
