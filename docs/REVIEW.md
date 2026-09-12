# Design review record (autoplan, 2026-09-12)

Reviewed: `DESIGN.md` and `SCHEMA-CHANGES.md` before any code. Four independent reviewers with
fresh context (Claude subagents; Codex is not installed, so every dual voice is `[subagent-only]`):
a spec reviewer (five dimensions), a CEO/strategy reviewer, a product designer and a senior
engineer. Every finding was decided with the six autoplan principles (completeness, boil the
lake, pragmatic, DRY, explicit over clever, bias to action). The premise gate that autoplan
normally puts to a human was auto-confirmed because the mission delegated routine decisions and
the premises come from the mission itself. The mission's definition of done is fixed scope:
reviewers' proposals to cut it were rejected.

## Phase 1: CEO review (strategy and scope)

### 0A Premise challenge
1. The course dump is a domain spec, not code to port. Confirmed: the dump has no foreign keys,
   varchar dates and an overlap check by room type; porting would preserve the bugs.
2. The double-booking guard belongs in the database. Confirmed, with the alternative recorded
   (ADR-001: `room_night` unique row versus a PostgreSQL exclusion constraint; `room_night` wins
   on portability to H2 and on the availability strip being a plain query).
3. Server-rendered pages and one database are enough. Confirmed; nothing a visitor sees needs a
   SPA or a queue.
4. A public demo needs a nightly reset and abuse limits. Confirmed and strengthened (see decisions
   on reset strategy and proxy-aware rate limits).
5. Distribution = public URL + Docker image + repo with a runnable README. Confirmed.

### 0B Existing code leverage
Greenfield repository. The only existing artifacts are the course dump, diagrams and Swing
sources, used as a specification (read, never copied). The portfolio repo supplies the shape of the
`projects.ts` entry.

### 0C Dream state
```
CURRENT STATE              THIS PLAN                          12-MONTH IDEAL
Swing + MySQL course  -->  Hosted Spring Boot app, tested,  -->  Same app, plus rates by season,
project, no tests,         CI-deployed, nightly reset,           email confirmations, multi-room
double booking possible    reviewer-readable schema/tests        bookings, real payments (out of scope)
```

### 0C-bis Implementation alternatives
| Approach | Effort | Risk | Verdict |
|---|---|---|---|
| A. Minimal monolith, rules in controllers | S | Med | Rejected: the TDD requirement needs rules that exist as classes. |
| B. Monolith with a pure domain core (policies tested without Spring), services in transactions | M | Low | Chosen. |
| C. Hexagonal / event-sourced booking aggregate | L | High | Rejected: no visible gain at this size. |

### 0D Mode: SELECTIVE EXPANSION. Expansion candidates and rulings
| Candidate (from CEO voice) | Ruling | Principle |
|---|---|---|
| README as the primary artifact: diagram, badge, "the interesting part" linked to its test, 30-second click path | Accepted | P1 |
| "Under the hood" strip on the landing page (commit, DB vendor, last reset) | Accepted (small footer strip; links to `/about`) | P1 |
| "Run the race" button: fire 10 concurrent bookings for one room, show 1 created / 9 rejected live | Accepted: `POST /api/demo/race`, far-future night in a demo room, winner cancelled afterwards | P1, P2 |
| One-click "Log in as staff" on the login page | Accepted | P3 |
| Keep-warm ping during US daytime (GitHub Actions cron, every 10 minutes 13:00-05:00 UTC) | Accepted; README stays honest that outside those hours the first load can take up to a minute | P6 |
| Cut staff CRUD (maintenance, inventory, employees, complaints) to a later phase | Rejected: mission scope; every page gets MockMvc tests | mission |
| Postgres concurrency test mandatory in CI | Accepted: Testcontainers test runs in CI and CI fails if it is skipped there (`-Dinnkeeper.requireDocker=true`); an N-thread race test runs on H2 and Postgres | P1 |
| Drop the GUEST role as vestigial | Rejected: mission names three roles; GUEST gets a job (a logged-in guest sees bookings for the account email) | mission |
| Guest identity by email is unverified; make guest contact per-booking | Accepted: `guest` rows are per booking, email lower-cased and indexed, no unique constraint | P5 |
| Replace session unlock + email query strings with HMAC tokens | Partly: pages keep the session unlock; the API takes code+email in a POST body (`/api/bookings/lookup`, `/api/bookings/{code}/cancel`); the request log never records query strings | P5 |
| Rate limiter behind Render's proxy must key on `X-Forwarded-For` | Accepted: `server.forward-headers-strategy=native` plus a test | P1 |
| Reset by Flyway clean + migrate is fragile under live connections | Accepted: reset = one transaction that deletes child-first and reseeds through the same `SeedData` class the V2 migration uses, guarded by a JVM lock; in-app schedule and the Actions cron both call it; MANAGER gets a "Reset demo data" button | P5 |
| Scheduled workflows get disabled after 60 idle days | Accepted: Dependabot (maven + actions, monthly) keeps activity; the in-app schedule is the second path | P6 |
| Late check-out fine while the next booking holds the nights | Rejected as stated; rule reworded: the fine is one night's rate per night past check-out; the ledger is not rewritten for nights already gone | P5 |
| Invoice snapshots the tax rate; final invoices are immutable | Accepted: `invoice.tax_rate`; recompute only while the booking is open | P1 |
| `prod` must refuse to start without `DATABASE_URL` | Accepted | P5 |
| `@Version` on booking for staff transition races | Accepted, with a double check-out test | P1 |
| Post-deploy smoke job (health, create + cancel a booking through the API) | Accepted, in the deploy workflow | P1 |
| `X-Request-Id` echoed on responses and shown on error pages; `/about` shows last reset and pool stats | Accepted | P3 |
| `docs/adr/` with four decisions | Accepted (room_night, H2 vs Testcontainers, reset strategy, session vs token) | P1 |

### 0E Temporal interrogation
Hour 1: a visitor lands, dates first, books in under two minutes. Hour 6: the reviewer reads the
README, the schema and the race test. Month 3: the cron still runs (Dependabot activity), the
reset keeps the ledger clean, the badge is green.

### Review sections 1-10 (CEO)
1. Architecture: single deployable, one database, domain core without Spring; dependency graph in
   Phase 3. No issue.
2. Error and rescue: registry below.
3. Security: form login, BCrypt, role rules, CSRF on forms, API stateless, reset token, rate
   limit, honeypot, validation on every input, no PII beyond name/email/phone, no secrets in repo.
   Finding: Actuator must expose only `health`; springdoc UI public by design. Decided.
4. Data flow edge cases: booking create (happy / missing fields / empty dates / room taken),
   cancel (free / fee / not cancellable), check-in (early / late / wrong status), check-out
   (early / on time / late), reset (concurrent / mid-request). All in the test plan.
5. Code quality: records for DTOs, one class per policy, no catch-all exceptions.
6. Tests: unit, repository, MockMvc, Testcontainers, race test, smoke job.
7. Performance: per-page queries are bounded (a branch has under 40 rooms); the strip is one
   query; the dashboard is five queries. No N+1 by using fetch joins on booking pages.
8. Observability: request log line, request id, health, about page.
9. Deployment: Render Docker build, deploy from CI after tests, smoke check, rollback = redeploy
   previous commit through the Render API (documented).
10. Trajectory: nothing in the plan blocks seasonal rates or email later.

CEO consensus (subagent-only): premises valid, right problem, scope calibrated to the mission,
alternatives explored (ADRs added), risks covered after the rulings above, trajectory sound.

## Phase 2: Design review

UI scope detected (18 routes). Ratings before and after the rulings:

| Pass | Before | After | What changed |
|---|---|---|---|
| 1 Information architecture | 3 | 9 | Per-page hierarchy written into `DESIGN.md` section 4 (dates first on landing, price and strip first on room detail, code as hero on confirmation, departures then arrivals on the dashboard). |
| 2 Interaction states | 2 | 9 | State table added (below). |
| 3 User journey | 4 | 9 | Dates carry through query params; "Find my booking" in the header; policy shown before booking; cancel has a confirm step; expired session redirects to lookup with the code prefilled. |
| 4 AI slop risk | 6 | 8 | Palette corrected for contrast; brass is decorative only in light; no eyebrow labels, no numbered markers, one memorable element (the registry strip). |
| 5 Design system | 7 | 9 | Tokens fixed in `DESIGN.md` section 10 with contrast ratios. |
| 6 Responsive and accessibility | 3 | 9 | Strip wraps into weeks with roving tabindex; native date inputs; tables stack under 640 px; board stacks under 720 px; focus ring walnut (light) / brass (dark). |
| 7 Unresolved decisions | 9 open | 0 | Nav, buttons, chips, date format, theme toggle, form layout decided. |

Design consensus (subagent-only): hierarchy, states, journey, specificity, accessibility all
flagged and all resolved by edits to `DESIGN.md`.

### Interaction state table
| Feature | Loading | Empty | Error | Success | Partial |
|---|---|---|---|---|---|
| Landing search | Server-rendered; button disabled until dates valid (JS optional) | n/a | Inline field errors + summary that receives focus | Redirect to `/rooms?...` | n/a |
| Rooms list with dates | n/a | "Nothing free at any branch for Fri 3 Oct to Mon 6 Oct. Try shorter dates or another branch." | Validation as above | List with per-room total for the stay | Some branches sold out: branch shown with a "Sold out for these dates" line |
| Room detail strip | Server-rendered | n/a | n/a | Selected range shows nights and total | Requested dates blocked: "Not available 3 to 6 Oct. Next 3-night opening: 9 Oct." |
| Booking form | n/a | n/a | Field errors; 409 banner "Room 204 was just taken for those dates" with a link to alternatives; 429 page | PRG to `/bookings/{code}` | n/a |
| Confirmation | n/a | n/a | Unknown code or locked session redirects to `/my-bookings?code=` | Code as hero, copy button, "keep this code and your email" | Cancelled state shows fee and "recorded on your invoice" |
| Lookup | n/a | n/a | "We couldn't find a booking with that code and email." | Unlocks and redirects | n/a |
| Complaint | n/a | n/a | Field errors; 429 page | Ticket number page | n/a |
| Staff dashboard | n/a | "No departures today." / "No arrivals today." / "No open maintenance." / "Stock is above reorder levels." | n/a | n/a | Branch switcher; MANAGER sees all |
| Booking desk | n/a | Search with no matches: "No bookings match." | Wrong-status actions show a signal banner | Flash line "Checked in" / "Checked out" / "Payment recorded" | Balance due shown in signal when payments < total |
| Maintenance board | n/a | Empty columns say so | Field errors | Flash line | Done shows last 7 days only |
| Inventory | n/a | n/a | Field errors; quantity cannot go below zero | Flash line | Low stock rows highlighted |
| Employees | n/a | n/a | 403 page for STAFF | Flash line | Deactivated rows dimmed |

### Journey storyboard (visitor)
| Step | User does | User feels | Plan specifies |
|---|---|---|---|
| 1 | Lands | Oriented: demo strip says what this is | Strip copy, dates-first hero |
| 2 | Enters dates | Confident | Native date inputs with min/max, nights count |
| 3 | Picks a room | Informed | Rate, total for the stay, strip with dates pre-selected |
| 4 | Books | Sure of the cost | Price summary, policy line, "Confirm booking, $198" |
| 5 | Sees the code | Safe | Code as hero, copy button, email echoed large |
| 6 | Cancels later | Not tricked | Confirm step with the fee before the POST |

## Decision audit trail
| # | Phase | Decision | Principle | Rationale | Rejected |
|---|---|---|---|---|---|
| 1 | CEO | Approach B (domain core in a monolith) | P1, P5 | Rules as classes for TDD | A, C |
| 2 | CEO | Keep full mission scope | mission | Definition of done is fixed | Phase-2 cut |
| 3 | CEO | `room_night` unique row + ADR | P5 | Portable, strip is a plain query | GiST exclusion |
| 4 | CEO | Race test on H2 and Postgres; CI fails if the Postgres test is skipped | P1 | The guard must be exercised on the real engine | Optional skip |
| 5 | CEO | Guest rows per booking, no unique email | P5 | Email is unverified | Unique email |
| 6 | CEO | Session unlock for pages; POST bodies for API lookup and cancel | P5 | No query-string PII, no token scheme | HMAC tokens |
| 7 | CEO | Reset = transactional delete + reseed with a JVM lock | P5 | No DDL under live connections | Flyway clean |
| 8 | CEO | Keep GUEST role with a job | mission | Three roles required | Delete role |
| 9 | CEO | Late fine only, ledger untouched | P5 | Past nights need no protection | Claim nights |
| 10 | CEO | Race demo button | P1 | Makes the guard visible | Skip |
| 11 | CEO | Keep-warm cron | P6 | Cold starts are the first impression | Skip |
| 12 | CEO | Dependabot monthly | P6 | Keeps scheduled workflows alive | Skip |
| 13 | CEO | `@Version` on booking | P1 | Staff races | UPDATE ... WHERE |
| 14 | CEO | Invoice tax-rate snapshot, immutable when final | P1 | History must not move | Live rate |
| 15 | CEO | `prod` fails fast without `DATABASE_URL` | P5 | No silent H2 in prod | Fallback |
| 16 | Design | Dates-first hero, demo strip above header | P1 | Visitor's job is dates | Branch-first |
| 17 | Design | Server-rendered strip, JS adds selection only | P5 | Works without JS | JS-only |
| 18 | Design | Cancel confirm step (GET) before POST | P1 | Fee shown before commitment | Immediate POST |
| 19 | Design | Contrast-corrected tokens; brass decorative in light | P1 | 2.79:1 fails AA | Brass text |
| 20 | Design | Strip as weeks with roving tabindex | P1 | 60 columns fail at 400 px | Single row |
| 21 | Design | Native date inputs | P5 | Mobile pickers for free | Custom picker |
| 22 | Design | Departures then arrivals ledgers first on the dashboard | P1 | Desk's morning order | Equal tiles |
| 23 | Design | Board stacks under 720 px with per-card buttons | P5 | No drag and drop | Kanban only |
| 24 | Design | Keep "first load can take up to a minute" on the landing strip | mission | Mission requires it | Remove |

## Phase 3: Engineering review

### Step 0 scope challenge
Greenfield; every sub-problem maps to a new class. The complexity check triggers (far more than
8 files) and is accepted: the mission's definition of done fixes the scope. Built-ins used instead
of custom code: Spring Boot structured logging, Spring Security form login and CSRF, Bean
Validation, springdoc, Flyway, Testcontainers `@ServiceConnection`, Spring Boot build-info,
git-commit-id plugin. Custom code kept small: the rate limiter (no library) and the request id
filter. Distribution: Dockerfile, GHCR push, Render deploy from CI, README `docker run`.

### Architecture (ASCII)
```
                      +-------------------- web (Thymeleaf pages) --------------------+
 browser ---------->  | PageControllers  (public, booking, staff, about, login, error) |
                      +---------------------------+------------------------------------+
                      +-------------------- api (JSON) --------------------------------+
 curl / OpenAPI UI -> | ApiControllers   (branches, rooms, bookings, demo race)        |
                      +---------------------------+------------------------------------+
                                                  |
                 +--------------------------------v----------------------------------+
                 | services: BookingService (TransactionTemplate boundary),          |
                 |   AvailabilityService, InvoiceService, StaffDeskService,          |
                 |   MaintenanceService, InventoryService, ComplaintService,         |
                 |   EmployeeService, ResetService, BuildInfoService, RaceDemoService|
                 +-----+------------------------------------+-----------------------+
                       |                                    |
        +--------------v------------+          +------------v-------------+
        | domain (pure Java, Clock) |          | repositories (Spring Data)|
        | StayPeriod, OccupancyRule,|          | + SeedData (JdbcTemplate) |
        | InvoiceCalculator,        |          +------------+--------------+
        | CancellationPolicy,       |                       |
        | CheckInOutPolicy, Codes   |          +------------v--------------+
        +---------------------------+          | Flyway V1 (SQL) V2 (Java) |
                                               | H2 (demo/test) | Postgres |
 filters: RequestLoggingFilter (X-Request-Id, MDC), RateLimitFilter (X-Forwarded-For)
 security: api chain (stateless, no CSRF) | page chain (form login, CSRF, roles)
 schedules: ResetScheduler (03:00 Chicago) | GitHub Actions cron (09:30 UTC) -> POST /internal/reset
```

### Rulings on the engineering voice
| Finding | Ruling | Principle |
|---|---|---|
| Unique violation cannot be caught inside `@Transactional`; translate at the boundary by constraint name (H2 upper-cases names) | Accepted; facade + `TransactionTemplate`; case-insensitive match; retry codes 3 times | P5 |
| Declare `GenerationType.IDENTITY`; seed without explicit ids | Accepted | P5 |
| `@Version` on booking; parking via conditional update; 409 on stale updates | Accepted | P1 |
| Late check-out and the next guest's nights | Fine only; overstay blocks availability until check-out | P5 |
| `open-in-view=false`, DTOs to templates, one `Clock` | Accepted | P5 |
| Branch-timezone today; `Duration` deadline; `ChronoUnit.DAYS` | Accepted with DST tests | P1 |
| `tax_rate NUMERIC(5,4)`; invoice total check constraint | Accepted | P1 |
| No `TEXT`; `Instant` with millisecond clock; `flyway-database-postgresql` | Accepted | P1 |
| Reset: no Flyway clean; lock; drop or guard the in-app schedule | Accepted: delete-and-reseed, lock, stale-skip keeps both triggers | P5 |
| `ResetGate` filter answering 503 during reset | Rejected: the reset is one short transaction; MVCC readers see old or new data | P3 |
| Invoice rebuild via `orphanRemoval`; no fines after check-out | Accepted | P5 |
| `@DataJpaTest` with `replace = NONE`; Testcontainers `@ServiceConnection`; `assumeTrue` message; CI requires Docker | Accepted | P1 |
| Missing tests (deadline, DST, UTC-vs-Chicago today, every template, limiter, honeypot, reset token cases, wrong email 404, version conflict, about without git.properties, occupancy) | All added to the test plan | P1 |
| Reset token: 404 when unset, constant-time compare, CSRF exempt | Accepted | P1 |
| Limiter behind proxy: forward headers, bounded map | Accepted without Caffeine (bounded map + hourly expiry) | P5 |
| Emails in query strings; cancel as a code oracle; lookup rate limited | Accepted (POST bodies, email checked first, lookup limited) | P1 |
| Every visitor is MANAGER: warn on forms, mask emails, robots noindex, complaint linking needs code + email | Accepted | P1 |
| Two filter chains; springdoc paths; actuator health only; validation bounds; no stack traces; CSP | Accepted | P1 |
| 0.1 CPU: layered jar, AppCDS, JVM flags, Tomcat 20 threads, precomputed BCrypt, curl retries | Accepted | P1 |
| Neon: JDBC URL mapping, Hikari sizing, no keepalive, liveness health check | Accepted | P1 |
| Strip server-rendered from one query; `from` = branch today | Accepted | P5 |
| Sessions die with the instance; actor = username; no devtools | Accepted | P3 |

### Spec reviewer (five dimensions, score 7/10 before fixes)
Thirty-four numbered issues; every one is either already covered by a ruling above or resolved in
the revised `DESIGN.md` and `SCHEMA-CHANGES.md`: seed data section, CI section, "today"
definition, overstay handling, early check-out arithmetic, invoice status lifecycle, occupancy
rule, parking release, no-show statement, lookup rate limit, money checks, tax-rate type,
diagram cardinalities, out-of-service room actions, GUEST role job, 302 versus 401 in tests,
cron zones, enumerations listed, code alphabet and retry, locked-page redirect, staff branch scope,
dashboard definitions, DST arithmetic, security matcher order, `cancellationFeeNow` exposed,
`/api/availability` removed, structured logging via Spring Boot, H2 URL flags,
`@AutoConfigureTestDatabase(replace = NONE)`, seed reuses domain classes, ephemeral disk noted,
health poll before the reset POST, limiter off in tests, reset CSRF exemption, springdoc paths.
Rejected: single image path through GHCR (Render builds from the Dockerfile; GHCR is an artifact,
with an image-backed fallback recorded) and committing the public URL to PostgreSQL regardless of
credentials (the mission's hosting section defines the fallback).

### Error and rescue registry
| Codepath | What can go wrong | Exception | Rescued | Action | User sees |
|---|---|---|---|---|---|
| `BookingService.create` | room taken between check and insert | `DataIntegrityViolationException` (uq_room_night) | Y | translate to `RoomUnavailableException` | 409 page/JSON "Room was just taken" |
| `BookingService.create` | code collision | `DataIntegrityViolationException` (uq_booking_code) | Y | regenerate, retry 3x | nothing |
| `BookingService.create` | invalid dates / occupancy | `BookingRuleException` | Y | field errors | inline messages |
| `BookingService.cancel/checkIn/checkOut` | wrong state | `IllegalBookingStateException` | Y | 409 | signal banner with the reason |
| same | stale version | `ObjectOptimisticLockingFailureException` | Y | 409 | "This booking changed; reload" |
| `ParkingService.assign` | space just taken | conditional update returns 0 | Y | `ParkingUnavailableException` 409 | message on the check-in form |
| `ResetService.reset` | already running | `tryLock` false | Y | 409 | "Reset already running" |
| `ResetService.reset` | SQL failure mid-way | `DataAccessException` | Y | transaction rolls back, 500 logged with request id | error page with request id |
| `/internal/reset` | bad or missing token | none | Y | 404 / 403 | nothing |
| `RateLimitFilter` | over limit | none | Y | 429 | designed 429 page or JSON |
| `/about` | no git.properties | `ObjectProvider` empty | Y | "unknown" | commit "unknown" |
| `AvailabilityService` | days > 90 or bad dates | `MethodArgumentNotValidException` / `ConstraintViolationException` | Y | 400 | field errors |
| Actions cron | instance asleep | curl timeout | Y | health poll up to 5 min, `--retry 5` | n/a |
| Neon suspended | first query slow | Hikari init timeout | Y | `initialization-fail-timeout=60000` | first load slow |

### Failure modes registry
| Codepath | Failure mode | Rescued | Test | User sees | Logged |
|---|---|---|---|---|---|
| booking create race | two winners | Y (constraint) | Y (race test H2 + PG) | 409 | Y |
| booking create | partial rows after failure | Y (one transaction) | Y | 409 | Y |
| cancel after deadline | fee not applied | n/a | Y (deadline tests) | fee line | Y (event) |
| check-in on wrong day | allowed | Y (policy) | Y | banner | Y |
| double check-out | second succeeds | Y (@Version + state) | Y | 409 | Y |
| reset during request | 500s | Y (single transaction) | Y (reset test) | old or new data | Y |
| reset twice | double work | Y (stale skip) | Y | no-op | Y |
| rate limiter behind proxy | site-wide lockout | Y (forwarded headers) | Y | correct per-IP | Y |
| unlocked code after reset | 500 | Y (404 -> redirect) | Y | lookup page with message | Y |
| strip with no JS | empty box | Y (server-rendered) | Y (MockMvc) | links per night | n/a |
No row is unrescued, untested and silent: no critical gaps.

### Test coverage diagram
```
CODE PATHS                                             USER FLOWS
[+] domain/*Policy, InvoiceCalculator, StayPeriod      [+] search -> room -> book -> confirm -> invoice -> cancel
    +-- every rule 1-13 ...................... unit        +-- MockMvc booking flow with session unlock ...... web
[+] BookingService                                     [+] lookup by code + email ............................ web
    +-- create / race / collision / rules ... service      [+] complaint with and without a booking ............ web
    +-- cancel / check-in / check-out / fines / payments   [+] staff: login -> desk -> check in -> add fine ->
        / parking / version .................. service          check out -> record payment ..................... web
[+] AvailabilityService (dates, guests, overstay) svc  [+] maintenance create / start / done / out of service web
[+] ResetService (idempotent, stale, forced, 409) svc  [+] inventory adjust / add ............................ web
[+] SeedData counts, today's arrivals ..... repository [+] employees (MANAGER) and 403 for STAFF ............. web
[+] Security chains, CSRF, roles, reset token ..... web [+] API create -> lookup -> cancel, 400/404/409/429 ... web
[+] RateLimitFilter, honeypot, RequestLoggingFilter web [+] race demo endpoint ................................. web
[+] Postgres: context starts, V1+V2, race, reset .. TC  [+] screenshots 1280/400 light/dark + console ........ browser
```

### Worktree parallelization
Sequential implementation: every task shares `src/main/java` and the schema; the subagent
per task pattern keeps the lanes serial with a review between tasks.

### Completion summary
- Scope: accepted as the mission defines it.
- Architecture: 5 issues found, all decided. Code quality: 3 (DTOs, Clock, orphanRemoval), decided.
- Tests: diagram produced; 11 gaps identified, all added to the plan. Performance: 4 (0.1 CPU,
  Neon pool, strip query, dashboard queries), decided.
- NOT in scope: seasonal rates, email confirmations, online payment collection, multi-room
  bookings, no-show automation, a token-based booking access scheme, a 503 gate during reset,
  Caffeine, lazy initialisation, a single-image deploy path through GHCR (fallback only).
- What already exists: nothing to reuse; the course code is a specification only.
- Dream state delta: this plan reaches the hosted, tested, reviewer-readable app; the ideal adds
  the items above.
- Voices: subagent-only for every phase (Codex not installed). CEO consensus 6/6 after rulings;
  design 7/7; eng 6/6.
- Lake score: 31 of 34 recommendations chose the complete option; the three rejections are
  recorded with reasons.
- Cross-phase themes: the transaction boundary for the unique constraint (CEO, eng, spec), proxy
  awareness of the rate limiter (CEO, eng, spec), reset strategy (CEO, eng, spec), timezone of
  "today" (eng, spec). High-confidence signals; all resolved in the spec.
