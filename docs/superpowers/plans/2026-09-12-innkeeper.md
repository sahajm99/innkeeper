# Innkeeper Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build and host Innkeeper, a Spring Boot 3.5 / Java 21 rewrite of a university hotel-management course project: public booking site, staff desk, JSON API, tests gating CI, Docker image, Render deployment with a nightly reset.

**Architecture:** One deployable: Thymeleaf pages and a JSON API over services that orchestrate a pure-Java domain core (stay dates, invoice arithmetic, cancellation and check-in/out policies) inside explicit transactions; Spring Data JPA over a Flyway-managed schema whose `room_night` unique row is the double-booking guard; H2 in PostgreSQL mode for tests and the `demo` profile, PostgreSQL in `prod`.

**Tech Stack:** Spring Boot 3.5.16, Java 21, Maven, Spring Web MVC, Spring Data JPA (Hibernate 6.6), Spring Security 6, Bean Validation, Flyway 11, Thymeleaf + extras-springsecurity6, springdoc-openapi 2.8.17, H2 2.3, PostgreSQL 42.7, Testcontainers 1.21, JUnit 5, AssertJ, MockMvc, git-commit-id-maven-plugin 9.0.2, Docker, GitHub Actions, Render, Neon.

**Spec:** `docs/DESIGN.md` (binding), with `docs/SCHEMA-CHANGES.md`, `docs/REVIEW.md` (rulings) and `docs/DECISIONS.md`.

## Global Constraints

- Java 21, Spring Boot 3.5.16 parent, Maven; build with `mvn -q`. No Lombok, no MapStruct, no frontend framework, no CDN: every font, stylesheet and script is a file under `src/main/resources/static`.
- Base package `io.github.sahajm99.innkeeper`. Sub-packages: `config`, `domain`, `model`, `repository`, `seed`, `service`, `web`, `api`, `ops`.
- Every commit authored as `sahajm99 <64627746+sahajm99@users.noreply.github.com>` (repo-local config already set). Commit messages are one line, conventional style (`feat:`, `test:`, `chore:`), and carry **no trailer of any kind** (no `Co-Authored-By`, no `Signed-off-by`, nothing after the message). Implementers commit their own task; the controller pushes.
- Files use LF line endings and four-space indentation for Java, two spaces for YAML, HTML, CSS, JS, Markdown.
- Never copy code from the course project; never read the folder `UNT_Course_Work`. Never commit secrets; `.env.example` holds placeholders.
- Seed data is obviously fictional: guest and employee surnames from {Example, Sample, Placeholder, Fixture, Specimen, Mockup, Testcase, Stub, Dummy, Demo}; every email on `example.com`; no phone numbers at all in seed data; no identity numbers anywhere.
- Money: `BigDecimal`, scale 2, `RoundingMode.HALF_UP`, rounded once per computed line. Dates: `LocalDate`; instants: `Instant`; every "today" is `BranchDates.today(clock, branch.zone())`. Every policy and service takes the single `Clock` bean; nothing calls `LocalDate.now()` or `Instant.now()` without it.
- Hibernate `spring.jpa.hibernate.ddl-auto=validate` and `spring.jpa.open-in-view=false` in every profile; entities use `GenerationType.IDENTITY` and `@Enumerated(EnumType.STRING)`; templates receive DTOs or detached data, never lazy proxies.
- H2 URLs carry `MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH`. Repository tests use `@DataJpaTest` with `@AutoConfigureTestDatabase(replace = Replace.NONE)`.
- Tests are written before the code they cover (TDD): write the failing test, run it, see it fail, implement, run it, see it pass. Every test asserts behaviour, never only "does not throw".
- Test profile (`src/test/resources/application-test.yml`) turns the rate limiter off (`innkeeper.rate-limit.enabled=false`); the limiter's own test turns it on with `@TestPropertySource`.
- Roles are exactly `GUEST`, `STAFF`, `MANAGER`. Demo accounts: `guest / guest123`, `staff / staff123`, `manager / manager123`.
- Design rules for every page: the tokens, typefaces and layout in `docs/DESIGN.md` section 10; no inline `<script>` or `<style>` (CSP `default-src 'self'`); visible focus states; responsive to 400 px; light and dark; `prefers-reduced-motion` respected; every form shows server-side validation messages inline and in a summary that receives focus; dates render as `EEE d MMM yyyy` ("Fri 3 Oct 2026").
- Commands run from the repo root `C:\Users\sahaj\OneDrive\Desktop\Experiments\projects\active\innkeeper` in Git Bash: `mvn -q -B test -Dtest=ClassName` for one class, `mvn -q -B verify` for everything. The Postgres Testcontainers tests need Docker Desktop, which is running on this machine; they skip with a message when Docker is unavailable.

## File structure (created across the tasks)

```
pom.xml, Dockerfile, .dockerignore, render.yaml, .env.example, README.md, LICENSE
.github/workflows/ci.yml, nightly-reset.yml, keep-warm.yml; .github/dependabot.yml
src/main/java/io/github/sahajm99/innkeeper/
  InnkeeperApplication.java
  config/    ClockConfig, InnkeeperProperties, SecurityConfig, OpenApiConfig, WebConfig
  domain/    BookingStatus, InvoiceLineKind, BookingRuleException, RoomUnavailableException,
             IllegalBookingStateException, NotFoundException, StayPeriod, OccupancyRule,
             InvoiceCalculator, CancellationPolicy, CheckInOutPolicy, ConfirmationCodes, BranchDates
  model/     Branch, RoomType, Room, RoomStatus, Guest, Employee, UserAccount, Role, Booking, RoomNight,
             Invoice, InvoiceStatus, InvoiceLine, Payment, PaymentMethod, Fine, BookingEvent,
             BookingEventType, MaintenanceTeam, MaintenanceRequest, Priority, MaintenanceStatus,
             InventoryItem, ParkingSpace, ParkingKind, Complaint, ComplaintCategory, ComplaintStatus,
             AppMetadata
  repository/ one Spring Data interface per entity (BranchRepository ... AppMetadataRepository)
  seed/      SeedData, DemoAccounts, V2__seed_data
  service/   BookingService, CreateBookingCommand, AvailabilityService, InvoiceService,
             StaffDeskService, MaintenanceService, InventoryService, ComplaintService,
             EmployeeService, ResetService, AboutService, RaceDemoService, ConstraintNames
  web/       PublicPagesController, BookingPagesController, MyBookingsController, ComplaintController,
             LoginController, AboutController, StaffDeskController, StaffBookingController,
             MaintenanceController, InventoryController, StaffComplaintController, EmployeeController,
             ErrorPagesController, PageExceptionHandler, BookingAccess, Flash, form/*Form
  api/       BranchApiController, RoomApiController, BookingApiController, DemoApiController,
             ApiExceptionHandler, dto/*
  ops/       RequestLoggingFilter, RateLimiter, RateLimitFilter, ResetController, ResetScheduler
src/main/resources/
  application.yml, application-demo.yml, application-prod.yml
  db/migration/V1__schema.sql (present, tested on H2 and PostgreSQL 16)
  templates/ layout.html, fragments/*.html, index.html, rooms.html, room.html, book.html,
             booking.html, booking-cancel.html, invoice.html, my-bookings.html, complaint.html,
             complaint-filed.html, about.html, login.html, error/403.html 404.html 429.html 500.html,
             staff/desk.html bookings.html booking.html maintenance.html inventory.html
             complaints.html employees.html
  static/    css/innkeeper.css, css/print.css, js/theme.js, js/strip.js, js/race.js, js/copy.js,
             fonts/*.woff2 + OFL-*.txt (present), robots.txt, favicon.svg
src/test/java/io/github/sahajm99/innkeeper/ ... mirrors main; src/test/resources/application-test.yml
docs/  DESIGN.md, SCHEMA-CHANGES.md, DECISIONS.md, PROGRESS.md, REVIEW.md, adr/*.md, screenshots/*.png
```

---

### Task 1: Project skeleton, profiles and the V1 migration under test

**Files:**
- Create: `pom.xml`, `src/main/java/io/github/sahajm99/innkeeper/InnkeeperApplication.java`, `config/ClockConfig.java`, `config/InnkeeperProperties.java`, `src/main/resources/application.yml`, `application-demo.yml`, `application-prod.yml`, `src/test/resources/application-test.yml`, `src/test/java/io/github/sahajm99/innkeeper/InnkeeperApplicationTests.java`, `.env.example`
- Existing: `src/main/resources/db/migration/V1__schema.sql` (do not edit)

**Interfaces:**
- Produces: `Clock` bean (`Clock.tick(Clock.systemUTC(), Duration.ofMillis(1))`); `InnkeeperProperties` (`@ConfigurationProperties(prefix = "innkeeper")`, record-style class with `String resetToken`, `RateLimit rateLimit` {`boolean enabled`, `int perHour`}, `String timezone`); profiles `demo`, `prod`, `test`.

- [ ] **Step 1: Write the failing context test**

```java
package io.github.sahajm99.innkeeper;

@SpringBootTest
@ActiveProfiles("test")
class InnkeeperApplicationTests {
    @Autowired JdbcTemplate jdbc;

    @Test
    void flywayAppliesTheSchema() {
        Integer applied = jdbc.queryForObject(
            "select count(*) from flyway_schema_history where success = true", Integer.class);
        assertThat(applied).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from room_night", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from app_metadata", Integer.class)).isZero();
    }
}
```

- [ ] **Step 2: Write `pom.xml`** with parent `spring-boot-starter-parent` 3.5.16, `java.version` 21, dependencies: starter-web, starter-thymeleaf, starter-data-jpa, starter-security, starter-validation, starter-actuator, `org.thymeleaf.extras:thymeleaf-extras-springsecurity6`, `flyway-core`, `flyway-database-postgresql`, `org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.17`, `com.h2database:h2` (runtime), `org.postgresql:postgresql` (runtime); test: starter-test, spring-security-test, spring-boot-testcontainers, `org.testcontainers:postgresql`, `org.testcontainers:junit-jupiter`. Plugins: `spring-boot-maven-plugin` with the `build-info` goal; `io.github.git-commit-id:git-commit-id-maven-plugin:9.0.2` (`revision` goal, `failOnNoGitDirectory=false`, `failOnUnableToExtractRepoInfo=false`, `generateGitPropertiesFile=true`, `includeOnlyProperties` `git.commit.id.abbrev`, `git.commit.id.full`, `git.commit.time`, `git.branch`). Surefire runs `*Test` classes; pass `innkeeper.requireDocker` through with `<systemPropertyVariables>`.

- [ ] **Step 3: Write the configuration files**

`application.yml`:
```yaml
spring:
  application.name: innkeeper
  profiles.default: demo
  jpa:
    hibernate.ddl-auto: validate
    open-in-view: false
  flyway.locations: classpath:db/migration
  threads.virtual.enabled: false
server:
  forward-headers-strategy: native
  error.include-stacktrace: never
  error.include-message: never
  tomcat.threads.max: 20
management:
  endpoints.web.exposure.include: health
  endpoint.health.show-details: never
  endpoint.health.probes.enabled: true
springdoc:
  api-docs.path: /api/openapi
  swagger-ui.path: /api/docs
  paths-to-match: /api/**
innkeeper:
  reset-token: ${INNKEEPER_RESET_TOKEN:}
  rate-limit:
    enabled: true
    per-hour: 20
  timezone: America/Chicago
```
`application-demo.yml`: `spring.datasource.url: jdbc:h2:file:./data/innkeeper;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH`, `username: sa`, `password: ""`, `logging.structured.format.console: ecs`.
`application-prod.yml`: `spring.datasource.url: ${DATABASE_URL}`, `username: ${DATABASE_USERNAME}`, `password: ${DATABASE_PASSWORD}`, `spring.datasource.hikari: {maximum-pool-size: 3, minimum-idle: 0, idle-timeout: 30000, max-lifetime: 240000, initialization-fail-timeout: 60000}`, `logging.structured.format.console: ecs`. No defaults on the placeholders, so startup fails without them.
`application-test.yml`: `spring.datasource.url: jdbc:h2:mem:innkeeper;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1`, `username: sa`, `innkeeper.rate-limit.enabled: false`, `innkeeper.reset-token: test-reset-token`, `logging.level.org.hibernate.SQL: warn`.
`.env.example`: `SPRING_PROFILES_ACTIVE=demo`, `DATABASE_URL=jdbc:postgresql://host/db?sslmode=require`, `DATABASE_USERNAME=`, `DATABASE_PASSWORD=`, `INNKEEPER_RESET_TOKEN=change-me`.

- [ ] **Step 4: Write `InnkeeperApplication`** (`@SpringBootApplication`, `@EnableScheduling`, `@ConfigurationPropertiesScan`) and `ClockConfig`, `InnkeeperProperties`.

- [ ] **Step 5: Run `mvn -q -B test`** and confirm the context test passes (Flyway ran V1 on H2; Hibernate validate has nothing to validate yet).

- [ ] **Step 6: Commit** `chore: project skeleton, profiles and V1 schema under test`

---

### Task 2: Domain core, test first

**Files:**
- Create: `domain/BookingStatus.java`, `domain/InvoiceLineKind.java`, `domain/BookingRuleException.java`, `domain/RoomUnavailableException.java`, `domain/IllegalBookingStateException.java`, `domain/NotFoundException.java`, `domain/StayPeriod.java`, `domain/OccupancyRule.java`, `domain/InvoiceCalculator.java`, `domain/CancellationPolicy.java`, `domain/CheckInOutPolicy.java`, `domain/ConfirmationCodes.java`, `domain/BranchDates.java`
- Test: `src/test/java/.../domain/StayPeriodTest.java`, `OccupancyRuleTest.java`, `InvoiceCalculatorTest.java`, `CancellationPolicyTest.java`, `CheckInOutPolicyTest.java`, `ConfirmationCodesTest.java`, `BranchDatesTest.java`

**Interfaces (produced, used verbatim by later tasks):**
```java
public enum BookingStatus { CONFIRMED, CHECKED_IN, CHECKED_OUT, CANCELLED }
public enum InvoiceLineKind { ROOM_NIGHTS, TAX, FINE, CANCELLATION_FEE }
public class BookingRuleException extends RuntimeException { public BookingRuleException(String field, String message); public String field(); }
public class RoomUnavailableException extends RuntimeException { public RoomUnavailableException(String message); }
public class IllegalBookingStateException extends RuntimeException { public IllegalBookingStateException(String message); }
public class NotFoundException extends RuntimeException { public NotFoundException(String message); }

public record StayPeriod(LocalDate checkIn, LocalDate checkOut) {
    public static final int MAX_NIGHTS = 30;
    public static final int MAX_DAYS_AHEAD = 365;
    public static StayPeriod validated(LocalDate checkIn, LocalDate checkOut, LocalDate today); // rules 1 and 2
    public long nights();                       // ChronoUnit.DAYS.between
    public List<LocalDate> nightDates();        // [checkIn, checkOut)
    public boolean covers(LocalDate night);     // checkIn <= night < checkOut
    public boolean overlaps(StayPeriod other);  // checkIn < other.checkOut && other.checkIn < checkOut
}
public final class OccupancyRule { public static void check(int adults, int children, int maxOccupancy); } // rule 3
public final class InvoiceCalculator {
    public record FineLine(String reason, BigDecimal amount) {}
    public record Line(InvoiceLineKind kind, String description, int quantity, BigDecimal unitAmount, BigDecimal amount) {}
    public record Result(List<Line> lines, BigDecimal roomSubtotal, BigDecimal tax, BigDecimal fines, BigDecimal cancellationFee, BigDecimal total) {}
    public static Result calculate(int nightsCharged, BigDecimal nightlyRate, BigDecimal taxRate, List<FineLine> fines, BigDecimal cancellationFee);
}
public final class CancellationPolicy {
    public static final LocalTime CHECK_IN_TIME = LocalTime.of(15, 0);
    public static final Duration FREE_WINDOW = Duration.ofHours(48);
    public static Instant deadline(LocalDate checkIn, ZoneId zone);
    public static BigDecimal feeAt(Instant now, LocalDate checkIn, ZoneId zone, BigDecimal nightlyRate); // ZERO at or before deadline, nightlyRate after
    public static void assertCancellable(BookingStatus status); // IllegalBookingStateException unless CONFIRMED
}
public final class CheckInOutPolicy {
    public record CheckOutOutcome(int nightsCharged, int lateNights, LocalDate releaseNightsFrom) {} // releaseNightsFrom null when nothing to release
    public static void assertCanCheckIn(BookingStatus status, StayPeriod stay, LocalDate today); // rule 8
    public static CheckOutOutcome checkOut(BookingStatus status, StayPeriod stay, LocalDate today); // rule 9
}
public final class ConfirmationCodes {
    public static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    public static final Pattern PATTERN = Pattern.compile("INN-[A-Z2-9]{6}");
    public static String generate(Random random);
    public static boolean isValid(String code);
}
public final class BranchDates { public static LocalDate today(Clock clock, ZoneId zone); }
```

- [ ] **Step 1: Write the failing tests** (one class per policy). Required cases, each its own `@Test` with a descriptive name:
  - StayPeriod: check-out equal to check-in throws `BookingRuleException` with field `checkOut`; check-out before check-in throws; 31 nights throws (`checkOut`), 30 nights passes; check-in yesterday throws (`checkIn`); check-in today passes; check-in 366 days ahead throws, 365 passes; `nights()` of 2026-10-01 to 2026-10-04 is 3; `nightDates()` lists 1, 2, 3 October; `overlaps` true for touching-inside ranges and false for back-to-back (out == in).
  - OccupancyRule: adults 0 throws (field `adults`); adults 2 + children 1 with max 2 throws (field `children`); 2 + 0 with max 2 passes.
  - InvoiceCalculator: 3 nights x 89.00 at 0.1300 with no fines: subtotal 267.00, tax 34.71, total 301.71 and two lines; 3 x 89.00 at 0.0825 gives tax 22.03 (22.0275 rounded once); fines [40.00 "Smoking in room"] add a FINE line and are not taxed; cancellation fee 89.00 with 0 nights gives subtotal 0.00, tax 0.00, total 89.00 and exactly one line; total always equals subtotal + tax + fines + cancellation fee (property check over a few random inputs with a seeded `Random`).
  - CancellationPolicy: deadline for check-in 2026-10-03 in `America/Chicago` is 2026-10-01T15:00-05:00 (as `Instant`); `feeAt` one second before the deadline is ZERO; at the deadline is ZERO; one second after is the nightly rate; check-in 2026-11-02 (DST ended 2026-11-01): deadline is 2026-10-31T15:00-05:00, i.e. exactly 48 h before 2026-11-02T15:00-06:00; `assertCancellable(CHECKED_IN)` throws.
  - CheckInOutPolicy: check-in allowed on the check-in date and on the day after (late arrival), refused the day before (message contains "before"), refused on the check-out date, refused when status is CHECKED_IN; check-out on the check-out date: nightsCharged = nights, lateNights 0, releaseNightsFrom null; early (stay 1-4 Oct, today 3 Oct): nightsCharged 2, releaseNightsFrom 3 Oct; same-day (today 1 Oct): nightsCharged 1, releaseNightsFrom 2 Oct; late (today 6 Oct): nightsCharged 3, lateNights 2, release null; status CONFIRMED throws.
  - ConfirmationCodes: generated code matches PATTERN; never contains 0, O, 1, I; two seeds give different codes; `isValid("INN-ABC123")` false because of 1.
  - BranchDates: with a fixed clock at 2026-09-13T00:30:00Z, today in `America/Chicago` is 2026-09-12.

- [ ] **Step 2: Run** `mvn -q -B test -Dtest='io.github.sahajm99.innkeeper.domain.*Test'` and confirm compilation failures / red.
- [ ] **Step 3: Implement** each class minimally, in the order the tests fail. `InvoiceCalculator` builds lines: ROOM_NIGHTS ("3 nights x $89.00") when nightsCharged > 0, TAX ("Occupancy tax 13%") when tax > 0, one FINE per fine, CANCELLATION_FEE when > 0; every amount `setScale(2, HALF_UP)`.
- [ ] **Step 4: Run** the domain tests again; all green.
- [ ] **Step 5: Commit** `feat(domain): stay, occupancy, invoice, cancellation and check-in/out policies with tests`

---

### Task 3: JPA entities, enums, repositories and repository tests

**Files:**
- Create: everything under `model/` and `repository/` listed in the file structure
- Test: `src/test/java/.../repository/SchemaValidationTest.java`, `RoomNightRepositoryTest.java`, `RoomRepositoryTest.java`, `BookingRepositoryTest.java`, `CascadeTest.java`, plus `src/test/java/.../support/TestData.java` (builders that insert a branch, room type, room, guest and booking through repositories)

**Interfaces:**
- Consumes: `BookingStatus`, `InvoiceLineKind` from Task 2 (entities use `@Enumerated(EnumType.STRING)`).
- Produces: entities mapping every column of `V1__schema.sql` by name (snake_case columns, explicit `@Column(name = ...)`), `Booking.version` with `@Version`, `Branch.zone()` returning `ZoneId.of(timezone)`, `Invoice.lines` as `@OneToMany(mappedBy = "invoice", cascade = ALL, orphanRemoval = true) @OrderBy("lineOrder")`, and these repository methods:
```java
interface RoomNightRepository extends JpaRepository<RoomNight, Long> {
    @Query("select n.nightDate from RoomNight n where n.room.id = :roomId and n.nightDate >= :from and n.nightDate < :to")
    List<LocalDate> bookedNights(Long roomId, LocalDate from, LocalDate to);
    long deleteByBookingIdAndNightDateGreaterThanEqual(Long bookingId, LocalDate from);
    long deleteByBookingId(Long bookingId);
}
interface RoomRepository extends JpaRepository<Room, Long> {
    @Query("""
        select r from Room r join fetch r.roomType t join fetch r.branch b
        where r.status = io.github.sahajm99.innkeeper.model.RoomStatus.AVAILABLE
          and (:branchId is null or b.id = :branchId)
          and (:typeCode is null or t.code = :typeCode)
          and (:maxRate is null or r.nightlyRate <= :maxRate)
          and t.maxOccupancy >= :guests
          and not exists (select 1 from RoomNight n where n.room = r and n.nightDate >= :checkIn and n.nightDate < :checkOut)
          and (:checkIn > :today or not exists (select 1 from Booking o where o.room = r
               and o.status = io.github.sahajm99.innkeeper.domain.BookingStatus.CHECKED_IN and o.checkOutDate <= :today))
        order by b.name, r.roomNumber""")
    List<Room> findAvailable(Long branchId, String typeCode, BigDecimal maxRate, int guests, LocalDate checkIn, LocalDate checkOut, LocalDate today);
    List<Room> findByBranchIdOrderByRoomNumber(Long branchId);
    Optional<Room> findByBranchCodeAndRoomNumber(String branchCode, String roomNumber);
}
interface BookingRepository extends JpaRepository<Booking, Long> {
    Optional<Booking> findByConfirmationCode(String code);
    @Query("select b from Booking b join fetch b.room r join fetch r.branch join fetch b.guest where lower(b.guest.email) = lower(:email) order by b.checkInDate desc")
    List<Booking> findByGuestEmail(String email);
    @Query("select b from Booking b join fetch b.room r join fetch r.branch join fetch b.guest where b.status = io.github.sahajm99.innkeeper.domain.BookingStatus.CONFIRMED and b.checkInDate = :date and (:branchId is null or r.branch.id = :branchId) order by r.branch.name, r.roomNumber")
    List<Booking> arrivals(Long branchId, LocalDate date);
    @Query("select b from Booking b join fetch b.room r join fetch r.branch join fetch b.guest where b.status = io.github.sahajm99.innkeeper.domain.BookingStatus.CHECKED_IN and b.checkOutDate <= :date and (:branchId is null or r.branch.id = :branchId) order by b.checkOutDate, r.roomNumber")
    List<Booking> departures(Long branchId, LocalDate date);
    @Query("select count(b) from Booking b where b.status = io.github.sahajm99.innkeeper.domain.BookingStatus.CHECKED_IN and b.room.branch.id = :branchId")
    long occupiedRooms(Long branchId);
    @Query("select b from Booking b join fetch b.room r join fetch r.branch join fetch b.guest g where lower(b.confirmationCode) like lower(concat('%', :q, '%')) or lower(g.email) like lower(concat('%', :q, '%')) or lower(concat(g.firstName, ' ', g.lastName)) like lower(concat('%', :q, '%')) order by b.checkInDate desc")
    List<Booking> search(String q);
    List<Booking> findByCheckInDateOrderByCheckInDate(LocalDate date);
}
interface RoomTypeRepository { Optional<RoomType> findByCode(String code); List<RoomType> findAllByOrderBySortOrder(); }
interface UserAccountRepository { Optional<UserAccount> findByUsername(String username); }
interface InventoryItemRepository { List<InventoryItem> findByBranchIdOrderByName(Long branchId); @Query("select i from InventoryItem i join fetch i.branch where i.quantity <= i.reorderLevel and (:branchId is null or i.branch.id = :branchId) order by i.branch.name, i.name") List<InventoryItem> lowStock(Long branchId);
    @Modifying @Query("update InventoryItem i set i.quantity = i.quantity + :delta, i.updatedAt = :now where i.id = :id and i.quantity + :delta >= 0") int adjust(Long id, int delta, Instant now); }
interface ParkingSpaceRepository { List<ParkingSpace> findByBranchIdAndBookingIsNullAndKindOrderBySpaceNumber(Long branchId, ParkingKind kind); Optional<ParkingSpace> findByBookingId(Long bookingId);
    @Modifying @Query("update ParkingSpace p set p.booking.id = :bookingId where p.id = :spaceId and p.booking is null") int assign(Long spaceId, Long bookingId);
    @Modifying @Query("update ParkingSpace p set p.booking = null where p.booking.id = :bookingId") int release(Long bookingId); }
interface MaintenanceRequestRepository { List<MaintenanceRequest> findByBranchIdAndStatusOrderByPriorityDescCreatedAtAsc(...); @Query(...) List<MaintenanceRequest> board(Long branchId, Instant doneSince); long countByStatusAndBranchId(MaintenanceStatus status, Long branchId); }
interface ComplaintRepository { List<Complaint> findByStatusInOrderByCreatedAtAsc(Collection<ComplaintStatus>); long countByStatusNot(ComplaintStatus status); Optional<Complaint> findByTicketNumber(String); }
interface EmployeeRepository { List<Employee> findAllByOrderByBranchNameAscLastNameAsc(); Optional<Employee> findByEmail(String); }
interface AppMetadataRepository extends JpaRepository<AppMetadata, String> {}
interface InvoiceRepository { Optional<Invoice> findByBookingId(Long bookingId); }
interface PaymentRepository { List<Payment> findByInvoiceIdOrderByPaidAt(Long invoiceId); }
interface FineRepository { List<Fine> findByBookingIdOrderByIssuedAt(Long bookingId); }
interface BookingEventRepository { List<BookingEvent> findByBookingIdOrderByOccurredAt(Long bookingId); }
interface GuestRepository, BranchRepository (findByCode, findAllByOrderByName), MaintenanceTeamRepository (findByBranchIdOrderByName)
```
Priority ordering: `Priority` enum declared `LOW, NORMAL, URGENT` so `order by priority desc` puts URGENT first.

- [ ] **Step 1: Write the failing tests** (all `@DataJpaTest @AutoConfigureTestDatabase(replace = Replace.NONE) @ActiveProfiles("test")`):
  - `SchemaValidationTest`: context loads (Hibernate validate against V1 on H2 in PostgreSQL mode) and `flyway_schema_history` has one row.
  - `RoomNightRepositoryTest`: saving two `RoomNight` rows for the same room and night then `flush()` throws `DataIntegrityViolationException` whose message chain contains `uq_room_night` (case-insensitive); `bookedNights` returns only nights inside `[from, to)`; `deleteByBookingIdAndNightDateGreaterThanEqual` removes only later nights.
  - `RoomRepositoryTest`: `findAvailable` excludes a room with a night inside the range, includes it when the range ends on the booked night (back to back), excludes OUT_OF_SERVICE rooms, excludes rooms whose type sleeps fewer than `guests`, excludes a room with an overstaying CHECKED_IN booking when `checkIn == today`, includes it when `checkIn` is later, filters by branch, type code and max rate.
  - `BookingRepositoryTest`: `findByGuestEmail` is case-insensitive; `arrivals` returns CONFIRMED with check-in on the date only; `departures` returns CHECKED_IN with check-out on or before the date (includes overstays); `occupiedRooms` counts CHECKED_IN in the branch; saving a guest with an upper-case email throws `DataIntegrityViolationException` (check constraint `ck_guest_email_lower`).
  - `CascadeTest`: deleting a booking removes its room nights, invoice, invoice lines, events and fines; deleting a branch removes its rooms; deleting a room type referenced by a room throws.
- [ ] **Step 2: Run** `mvn -q -B test -Dtest='io.github.sahajm99.innkeeper.repository.*Test'`; red.
- [ ] **Step 3: Implement** entities and repositories. Getters and setters, no Lombok. `Guest.setEmail` lower-cases and trims. `Booking.stay()` returns `new StayPeriod(checkInDate, checkOutDate)`. `Branch.zone()` returns `ZoneId.of(timezone)`.
- [ ] **Step 4: Run** the repository tests and `mvn -q -B test`; all green (validate passes on every entity).
- [ ] **Step 5: Commit** `feat(model): JPA entities and repositories validated against the Flyway schema`

---

### Task 4: Seed data, the V2 Java migration and the PostgreSQL migration test

**Files:**
- Create: `seed/DemoAccounts.java`, `seed/SeedData.java`, `seed/V2__seed_data.java`, `src/test/java/.../support/DockerAvailable.java` (JUnit `ExecutionCondition` + `@EnabledIfDockerAvailable` annotation), `src/test/java/.../support/AbstractPostgresTest.java`
- Test: `seed/DemoAccountsTest.java`, `seed/SeedDataTest.java`, `PostgresMigrationTest.java`

**Interfaces:**
- Consumes: `InvoiceCalculator`, `StayPeriod`, `ConfirmationCodes`, `BranchDates` (Task 2); repositories only in tests.
- Produces:
```java
public final class DemoAccounts {
    public record Account(String username, String password, String role, String displayName, String employeeEmail) {}
    public static final Account GUEST = new Account("guest", "guest123", "GUEST", "guest@example.com", null);
    public static final Account STAFF = new Account("staff", "staff123", "STAFF", "Ben Sample", "ben.sample@example.com");
    public static final Account MANAGER = new Account("manager", "manager123", "MANAGER", "Ada Example", "ada.example@example.com");
    public static final String GUEST_HASH = "$2a$10$...";   // precomputed BCrypt of guest123, strength 10
    public static final String STAFF_HASH = "$2a$10$...";
    public static final String MANAGER_HASH = "$2a$10$...";
    public static String hashFor(Account account);
}
public class SeedData {
    public static final List<String> TABLES_CHILD_FIRST = List.of("booking_event", "payment", "invoice_line", "invoice", "fine",
        "room_night", "parking_space", "complaint", "booking", "guest", "maintenance_request", "maintenance_team",
        "inventory_item", "user_account", "employee", "room", "room_type", "branch", "app_metadata");
    public SeedData(JdbcOperations jdbc, Clock clock);
    public void seed();        // inserts everything below, relative to today in America/Chicago
    public void deleteAll();   // DELETE FROM each table in TABLES_CHILD_FIRST order
    public record Counts(int branches, int roomTypes, int rooms, int guests, int bookings, int roomNights, int invoices, int employees, int accounts, int teams, int requests, int inventoryItems, int parkingSpaces, int complaints) {}
    public static Counts expectedCounts(); // the literal counts of what seed() inserts
}
@Component public class V2__seed_data extends BaseJavaMigration { /* migrate(Context) -> new SeedData(new JdbcTemplate(new SingleConnectionDataSource(context.getConnection(), true)), clock).seed() */ }
```
Inserts use `PreparedStatement` with `connection.prepareStatement(sql, new String[]{"id"})` and read the generated id (never explicit ids). Invoices are computed with `InvoiceCalculator` (nights, the branch tax rate, fines, cancellation fee) so seed arithmetic cannot drift from the domain. Room nights are inserted for every CONFIRMED and CHECKED_IN booking (all nights) and for CHECKED_OUT bookings (nights up to the check-out). Events: CREATED for all; CHECKED_IN, CHECKED_OUT, CANCELLED, FINE_ADDED, PAYMENT_RECORDED as applicable; actor "seed".

Seed content (D = today in America/Chicago):
- Branches: `DEN` "Denton Square" (Denton, TX 76201, tax 0.1300, tagline "Twelve rooms on the courthouse square"), `FTW` "Fort Worth Stockyards" (Fort Worth, TX 76164, 0.1500, "Brick, brass and a quiet courtyard"), `AUS` "Austin Lakeline" (Austin, TX 78717, 0.1700, "A small inn by the water"). Addresses like "101 Example Street"; phones like "(000) 000-0100"; emails `denton@example.com` etc.; timezone `America/Chicago`.
- Room types: `STANDARD` "Standard Queen" (2, "One queen bed"), `DELUXE` "Deluxe King" (3, "One king bed and a sofa"), `SUITE` "Courtyard Suite" (4, "King bed and a separate sitting room").
- Rooms: DEN 101-106 STANDARD 89.00 floor 1, 201-204 DELUXE 129.00 floor 2, 301-302 SUITE 189.00 floor 3 (106 OUT_OF_SERVICE, notes "Plumbing repair"); FTW 101-105 STANDARD 99.00, 201-203 DELUXE 139.00, 301-302 SUITE 209.00; AUS 101-105 STANDARD 109.00, 201-203 DELUXE 149.00, 301-302 SUITE 229.00. Total 32 rooms.
- Employees: Ada Example (DEN, Front desk manager, hired 2023-03-01), Ben Sample (DEN, Front desk, 2024-06-15), Cleo Placeholder (FTW, Housekeeping lead, 2022-11-07), Dev Fixture (FTW, Maintenance, 2023-08-21), Eve Specimen (AUS, Front desk, 2025-01-13), Finn Mockup (AUS, Night auditor, 2024-02-05); emails `first.last@example.com`.
- Accounts: the three `DemoAccounts` (STAFF linked to Ben Sample, MANAGER to Ada Example).
- Teams per branch: Housekeeping (specialty "Rooms and linen"), Engineering ("Plumbing, electrical, HVAC"), Grounds ("Courtyard and parking"); emails `den-housekeeping@example.com` style.
- Maintenance requests: DEN 106 URGENT OPEN "Leaking bathroom faucet"; DEN 203 NORMAL IN_PROGRESS "Television remote missing" (Housekeeping, started D-1); FTW no room LOW OPEN "Lobby lamp flickers"; AUS 205 URGENT IN_PROGRESS "Air conditioning not cooling" (Engineering); AUS no room NORMAL DONE "Replace hallway carpet" (completed D-1). Reported by employee names.
- Inventory per branch: Bath towels (120 / reorder 60 / each), Queen sheet sets (40 / 30 / set), Toilet paper (DEN 18 / 24, others 60 / 24), Coffee pods (300 / 100 / each), Shampoo bottles (FTW 25 / 40, others 90 / 40), Key cards (80 / 50 / each). Two items are below reorder level: DEN toilet paper, FTW shampoo.
- Parking: DEN P1-P6 GUEST and S1-S2 STAFF; FTW P1-P4, S1-S2; AUS P1-P4, S1-S2.
- Complaints: DEN OPEN ROOM "Heater rattles at night" (guest Grace Example, linked to an in-house booking); FTW IN_PROGRESS SERVICE "Late housekeeping"; AUS RESOLVED NOISE "Parking lot noise" (resolution note, resolved D-3). Ticket numbers `CMP-` + 6 characters from the confirmation alphabet.
- Guests and bookings (room, check-in, check-out, status), all nights on the branch rate:
  | Guest | Room | In | Out | Status | Extra |
  |---|---|---|---|---|---|
  | Grace Example | DEN 101 | D-2 | D+1 | CHECKED_IN | complaint linked |
  | Hugo Sample | DEN 201 | D-1 | D+2 | CHECKED_IN | |
  | Iris Placeholder | FTW 101 | D-1 | D+3 | CHECKED_IN | fine 40.00 "Smoking in room" |
  | Juno Fixture | AUS 301 | D-2 | D+1 | CHECKED_IN | |
  | Kai Specimen | DEN 102 | D-1 | D | CHECKED_IN | departure today |
  | Lena Mockup | FTW 201 | D-2 | D | CHECKED_IN | departure today |
  | Milo Testcase | AUS 101 | D-3 | D | CHECKED_IN | departure today |
  | Nia Dummy | DEN 301 | D-3 | D-1 | CHECKED_IN | overstay |
  | Otto Stub | DEN 103 | D | D+2 | CONFIRMED | arrival today |
  | Pia Demo | FTW 102 | D | D+1 | CONFIRMED | arrival today |
  | Quinn Example | AUS 201 | D | D+3 | CONFIRMED | arrival today |
  | guest@example.com (Gus Sample) | DEN 202 | D+3 | D+5 | CONFIRMED | demo guest |
  | guest@example.com (Gus Sample) | AUS 302 | D+20 | D+23 | CONFIRMED | demo guest |
  | Rae Placeholder | FTW 301 | D+7 | D+9 | CONFIRMED | |
  | Sol Fixture | DEN 104 | D+14 | D+15 | CONFIRMED | |
  | Tess Specimen | AUS 102 | D+30 | D+33 | CONFIRMED | |
  | Uma Mockup | DEN 105 | D+40 | D+42 | CONFIRMED | |
  | Vic Testcase | DEN 101 | D-10 | D-8 | CHECKED_OUT | PAID, CARD |
  | Wren Dummy | FTW 103 | D-9 | D-6 | CHECKED_OUT | PAID, CASH, fine 25.00 "Lost key card" |
  | Xavi Demo | AUS 103 | D-7 | D-5 | CHECKED_OUT | PAID, CARD |
  | Yara Example | DEN 204 | D-6 | D-4 | CHECKED_OUT | PAID, CARD |
  | Zed Sample | FTW 202 | D-5 | D-3 | CHECKED_OUT | PAID, CASH |
  | Ana Stub | AUS 202 | D+10 | D+12 | CANCELLED | cancelled D-1, fee 0, invoice VOID |
  | Bo Fixture | DEN 302 | D+1 | D+3 | CANCELLED | cancelled D, fee 189.00, invoice OPEN |
  Totals: 24 bookings, 24 guests. Confirmation codes are generated with `ConfirmationCodes.generate(new Random(42))`, regenerated on collision inside the seeder.
- `app_metadata`: `seeded_at` = clock instant (ISO-8601), `last_reset_at` = same.

- [ ] **Step 1: Write the failing tests**
  - `DemoAccountsTest`: `new BCryptPasswordEncoder().matches("staff123", DemoAccounts.STAFF_HASH)` is true for all three accounts; hashes start with `$2a$10$`.
  - `SeedDataTest` (`@SpringBootTest @ActiveProfiles("test")`, `@Autowired JdbcTemplate`, repositories, `Clock`): after context start the counts of every table equal `SeedData.expectedCounts()`; `bookingRepository.arrivals(null, today)` has 3 rows; `departures(null, today)` has 4 rows (three due today and the overstay); `findByGuestEmail("guest@example.com")` has 2; every booking's invoice total equals `InvoiceCalculator` recomputation from its rows; the DEN toilet paper row is low stock; `deleteAll()` then `seed()` on the same `JdbcTemplate` yields the same counts again (idempotent); no `guest.email` or `employee.email` ends with anything but `@example.com`; no guest phone is set.
  - `PostgresMigrationTest` (`@SpringBootTest @ActiveProfiles("test") @EnabledIfDockerAvailable` with `@Container @ServiceConnection static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")`): context starts (validate against real PostgreSQL); `flyway_schema_history` has two successful rows; inserting a new guest through `GuestRepository` after the seed returns an id greater than every seeded id (identity sequence in step); the room_night unique constraint raises `DataIntegrityViolationException` on PostgreSQL.
  - `DockerAvailable` condition: enabled when `DockerClientFactory.instance().isDockerAvailable()`; otherwise, when system property `innkeeper.requireDocker` is `true`, enabled anyway (so the container start fails the test loudly in CI); otherwise disabled with reason "Docker is not available; skipping PostgreSQL tests".
- [ ] **Step 2: Run** the three test classes; red (no seed rows yet).
- [ ] **Step 3: Implement** `DemoAccounts` (compute the hashes once with `new BCryptPasswordEncoder(10).encode(...)` in a scratch `main`, paste the literals), `SeedData`, `V2__seed_data`.
- [ ] **Step 4: Run** `mvn -q -B test`; all green, including `PostgresMigrationTest` (Docker Desktop is running). Re-run `InnkeeperApplicationTests` expecting two Flyway rows now (update its assertion to 2).
- [ ] **Step 5: Commit** `feat(seed): fictional seed data as a Java migration, verified on H2 and PostgreSQL`

---

### Task 5: Booking, availability and invoice services with the race test

**Files:**
- Create: `service/CreateBookingCommand.java`, `service/ConstraintNames.java`, `service/BookingService.java`, `service/AvailabilityService.java`, `service/InvoiceService.java`, `service/BookingView.java` (detached read model used by pages and API: code, status, room number, room type name, branch name/code/zone, dates, nights, adults, children, guest names/email/phone, special requests, invoice totals and lines, payments, fines, events, cancellationFeeNow, cancellation deadline)
- Test: `service/BookingServiceTest.java`, `service/BookingRaceTest.java`, `service/AvailabilityServiceTest.java`, `service/InvoiceServiceTest.java`, `PostgresBookingRaceTest.java`

**Interfaces:**
```java
public record CreateBookingCommand(Long roomId, LocalDate checkIn, LocalDate checkOut, int adults, int children,
        String firstName, String lastName, String email, String phone, String specialRequests, String actor) {}
public final class ConstraintNames { public static String of(DataAccessException e); } // lower-cased constraint name from a ConstraintViolationException cause, else the lower-cased root message
public class BookingService {
    public Booking create(CreateBookingCommand cmd);          // not @Transactional; uses TransactionTemplate; translates uq_room_night -> RoomUnavailableException, uq_booking_code -> retry (3 attempts)
    public Booking requireByCode(String code);                 // NotFoundException
    public Optional<Booking> findByCodeAndEmail(String code, String email); // email compared lower-cased, checked before anything else
    public BigDecimal cancellationFeeNow(Booking booking);
    public Instant cancellationDeadline(Booking booking);
    @Transactional public Booking cancel(String code, String actor); // assertCancellable, fee, delete nights, release parking, rebuild invoice (VOID when total is zero), event CANCELLED
    public BookingView view(Booking booking);                  // @Transactional(readOnly = true), loads lines, payments, fines, events
}
public class AvailabilityService {
    public record Query(Long branchId, String typeCode, BigDecimal maxRate, int guests, LocalDate checkIn, LocalDate checkOut) {}
    public record Night(LocalDate date, boolean available) {}
    public List<Room> findAvailable(Query q);                             // today from the first branch or the app timezone when branchId is null
    public List<Night> strip(Room room, LocalDate from, int days);        // days clamped 1..90; room OUT_OF_SERVICE -> all unavailable
    public Optional<LocalDate> nextOpening(Room room, LocalDate from, int nights, int horizonDays);
}
public class InvoiceService {
    @Transactional public Invoice rebuild(Booking booking, int nightsCharged); // recompute lines and totals from booking rate, branch tax snapshot (taken once at creation), fines, cancellation fee; status OPEN, PAID when payments >= total and total > 0, VOID when total is zero and booking CANCELLED
    public BigDecimal balance(Invoice invoice);
}
```
`create` inside the template: upsert nothing (a new `Guest` row per booking), insert `Booking` (status CONFIRMED, nightlyRate = room rate), insert one `RoomNight` per night, `Invoice` via `rebuild(booking, nights)` with `taxRate` from the branch, `BookingEvent` CREATED, then `flush()`. The `DataIntegrityViolationException` is caught outside `execute`.

- [ ] **Step 1: Write the failing tests** (`@SpringBootTest @ActiveProfiles("test")`, fixed `Clock` via a `@TestConfiguration` `@Primary` bean at 2026-09-12T15:00:00Z unless the test says otherwise; use `TestData` helpers from Task 3 for extra rooms):
  - `BookingServiceTest`: creates a booking with nights, invoice (subtotal, tax at the branch rate, total), CREATED event and code matching the pattern; the guest email is stored lower-cased; a second booking for the same room and overlapping dates throws `RoomUnavailableException` and leaves no new booking, guest or room-night rows; back-to-back bookings both succeed; an OUT_OF_SERVICE room throws `RoomUnavailableException`; occupancy over the type limit throws `BookingRuleException`; `findByCodeAndEmail` with the wrong email is empty; `cancel` before the deadline sets CANCELLED, fee 0, invoice VOID, deletes nights and writes the CANCELLED event; `cancel` after the deadline (clock moved to 2026-10-03T12:00-05:00 for a 2026-10-03 check-in) records fee = nightly rate and invoice OPEN with total = fee; cancelling a CHECKED_IN booking throws `IllegalBookingStateException`; a seeded `Random` that produces an existing code makes `create` retry and succeed.
  - `BookingRaceTest`: 8 threads on a `CyclicBarrier` call `create` for the same room, dates D+60 to D+62; exactly one returns a booking, seven throw `RoomUnavailableException`; `room_night` count for the room in that range is 2; no orphan guests (guest count grew by exactly 1).
  - `AvailabilityServiceTest`: `strip` marks booked nights unavailable and clamps `days` to 90; `nextOpening` finds the first run of free nights; `findAvailable` with `guests` 4 returns suites only.
  - `InvoiceServiceTest`: rebuilding after adding a fine adds a FINE line and keeps the tax on room nights only; PAID when a payment equals the total; `balance` after a partial payment.
  - `PostgresBookingRaceTest` extends `AbstractPostgresTest`: the same race on PostgreSQL yields exactly one booking.
- [ ] **Step 2: Run** them; red.
- [ ] **Step 3: Implement** the services. `ConstraintNames.of` walks `getCause()` until an `org.hibernate.exception.ConstraintViolationException` with a non-null constraint name, else uses the deepest message; result lower-cased.
- [ ] **Step 4: Run** `mvn -q -B test`; all green on H2 and PostgreSQL.
- [ ] **Step 5: Commit** `feat(booking): create, cancel and availability with the room-night guard under a race test`

---

### Task 6: Staff services

**Files:**
- Create: `service/StaffDeskService.java`, `service/MaintenanceService.java`, `service/InventoryService.java`, `service/ComplaintService.java`, `service/EmployeeService.java`, `service/ParkingUnavailableException.java`
- Test: `service/StaffDeskServiceTest.java`, `MaintenanceServiceTest.java`, `InventoryServiceTest.java`, `ComplaintServiceTest.java`, `EmployeeServiceTest.java`

**Interfaces:**
```java
public class StaffDeskService {
    public record Occupancy(Branch branch, long occupied, long inService, int percent) {}
    public record Dashboard(List<Booking> departures, List<Booking> arrivals, List<Occupancy> occupancy, Map<Priority, Long> openMaintenance, List<InventoryItem> lowStock, long openComplaints, LocalDate today) {}
    public Dashboard dashboard(Long branchId);                       // branchId null = all branches
    @Transactional public Booking checkIn(String code, Long parkingSpaceId, String actor);   // rule 8; parking via ParkingSpaceRepository.assign, 0 rows -> ParkingUnavailableException; event CHECKED_IN
    @Transactional public CheckInOutPolicy.CheckOutOutcome checkOut(String code, String actor); // rule 9; delete nights from releaseNightsFrom; late fine "Late check-out (n nights)"; release parking; rebuild invoice; event CHECKED_OUT
    @Transactional public Fine addFine(String code, String reason, BigDecimal amount, String actor); // only CONFIRMED or CHECKED_IN; rebuild; event FINE_ADDED
    @Transactional public Payment recordPayment(String code, BigDecimal amount, PaymentMethod method, String reference, String actor); // only CHECKED_OUT; amount > 0 and <= balance; invoice PAID at zero balance; event PAYMENT_RECORDED
    public List<Booking> search(String q);
    public List<ParkingSpace> freeGuestSpaces(Long branchId);
    public Long branchForUsername(String username);                 // employee's branch for STAFF, null for MANAGER
}
public class MaintenanceService {
    public record Board(List<MaintenanceRequest> open, List<MaintenanceRequest> inProgress, List<MaintenanceRequest> done, List<Room> outOfService) {}
    public Board board(Long branchId);                               // done = completed in the last 7 days
    @Transactional public MaintenanceRequest create(Long branchId, Long roomId, String title, String description, Priority priority, String reportedBy, boolean takeRoomOutOfService); // refuses out-of-service when the room has room nights from today on (IllegalStateException with message "Room 106 has upcoming stays")
    @Transactional public MaintenanceRequest start(Long id); @Transactional public MaintenanceRequest done(Long id); // done returns the room to service when no other open request holds it
    @Transactional public MaintenanceRequest assignTeam(Long id, Long teamId);
    @Transactional public Room returnToService(Long roomId);
}
public class InventoryService { public List<InventoryItem> list(Long branchId); @Transactional public InventoryItem adjust(Long id, int delta); // IllegalArgumentException "Quantity cannot go below zero" when the atomic update touches 0 rows
    @Transactional public InventoryItem add(Long branchId, String name, String category, int quantity, int reorderLevel, String unit); public List<InventoryItem> lowStock(Long branchId); }
public class ComplaintService { public record FileCommand(Long branchId, String bookingCode, String bookingEmail, String guestName, String guestEmail, ComplaintCategory category, String description) {}
    @Transactional public Complaint file(FileCommand cmd); // links the booking only when code + email match; a code without a matching email throws BookingRuleException("bookingCode", "That code and email do not match a booking")
    public List<Complaint> open(); @Transactional public Complaint start(Long id); @Transactional public Complaint resolve(Long id, String note); }
public class EmployeeService { public List<Employee> list(); @Transactional public Employee add(Long branchId, String firstName, String lastName, String email, String position, LocalDate hiredOn); @Transactional public Employee deactivate(Long id); }
```

- [ ] **Step 1: Write the failing tests** (`@SpringBootTest @ActiveProfiles("test")` with a fixed clock; use seeded bookings by looking them up through `BookingRepository.arrivals`/`departures` and `TestData`):
  - StaffDesk: dashboard for all branches lists 4 departures (overstay first by date) and 3 arrivals, occupancy per branch with DEN occupied 4 of 11 in service (106 is out), open maintenance counts by priority, two low-stock items, one open complaint; check-in of an arrival sets CHECKED_IN, assigns the chosen free space, writes the event; check-in of a booking whose date is tomorrow throws `IllegalBookingStateException`; assigning an already taken space throws `ParkingUnavailableException` and does not check in; check-out on time sets CHECKED_OUT, releases parking, invoice OPEN with balance = total; early check-out (clock moved) deletes later nights and charges fewer nights; late check-out of the overstay adds a late fine of 2 x 189.00 and lists lateNights 2; adding a fine to a CHECKED_OUT booking throws; payment above the balance throws `IllegalArgumentException`; payment equal to the balance sets PAID; two concurrent check-outs of the same booking (two threads, barrier) leave one success and one `ObjectOptimisticLockingFailureException` or `IllegalBookingStateException`.
  - Maintenance: create with out-of-service on a room with upcoming nights throws with the message; on a free room sets OUT_OF_SERVICE; `done` returns the room to service; board `done` only includes the last 7 days.
  - Inventory: adjust -5 on 18 leaves 13; adjust -20 on 18 throws and leaves 18; add creates a row.
  - Complaint: filing with a matching code + email links the booking; with the wrong email throws `BookingRuleException`; without a code files unlinked; resolve sets RESOLVED and the note.
  - Employee: add then deactivate; add with a duplicate email throws `DataIntegrityViolationException`.
- [ ] **Step 2: Run**; red. **Step 3: Implement.** **Step 4: Run** all tests; green.
- [ ] **Step 5: Commit** `feat(staff): desk, maintenance, inventory, complaints and employees services`

---

### Task 7: Security, login and error pages

**Files:**
- Create: `config/SecurityConfig.java`, `config/AccountUserDetailsService.java`, `web/LoginController.java`, `web/ErrorPagesController.java`, `web/PageExceptionHandler.java`, `web/Flash.java`, `src/main/resources/templates/layout.html` (minimal, replaced by Task 9), `templates/login.html`, `templates/error/403.html`, `404.html`, `429.html`, `500.html`, `static/robots.txt`
- Test: `config/SecurityConfigTest.java`, `web/LoginPageTest.java`, `web/ErrorPagesTest.java`

**Interfaces:**
- Consumes: `UserAccountRepository`, `DemoAccounts`.
- Produces: two `SecurityFilterChain` beans: `apiChain` (`securityMatcher("/api/**")`, stateless, CSRF disabled, `permitAll`) ordered first; `pageChain` (form login at `/login`, `loginProcessingUrl("/login")`, `defaultSuccessUrl("/staff", false)`, logout at `/logout` redirecting to `/`; CSRF enabled and `ignoringRequestMatchers("/internal/reset")`; rules in order: `/staff/employees/**` `hasRole("MANAGER")`, `/staff/reset` `hasRole("MANAGER")`, `/staff/**` `hasAnyRole("STAFF","MANAGER")`, `/internal/reset` `permitAll` (the token is checked in the controller), everything else `permitAll`); headers: CSP `default-src 'self'; img-src 'self' data:; frame-ancestors 'none'`, referrer policy `SAME_ORIGIN`; `PasswordEncoder` = `BCryptPasswordEncoder`; `AccountUserDetailsService` loads by username with authorities `ROLE_<role>` and honours `enabled`. Cookie `SameSite=Lax` via `server.servlet.session.cookie.same-site: lax` in `application.yml`. `Flash` puts `message`/`error` strings into `RedirectAttributes`. `PageExceptionHandler` (`@ControllerAdvice(basePackages = "io.github.sahajm99.innkeeper.web")`) maps `NotFoundException` to 404, `IllegalBookingStateException` and `ObjectOptimisticLockingFailureException` and `RoomUnavailableException` to 409 pages (`error/409` reuses `error/500.html` layout with the message), and everything else falls to the `/error` mapping. `ErrorPagesController implements ErrorController` maps `/error` to the template by status and adds `requestId` from the response header `X-Request-Id` (set by Task 8; "n/a" until then).

- [ ] **Step 1: Write the failing tests** (`@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")`):
  - anonymous `GET /staff` -> 302 to `/login`; `GET /staff/employees` as `@WithMockUser(roles = "STAFF")` -> 403; as MANAGER -> not 403 (404 is acceptable until Task 12; assert status is not 401/403/302); `POST /staff/inventory/1/adjust` as STAFF without CSRF -> 403; `GET /api/openapi` anonymous -> 200 JSON; `GET /api/docs` anonymous -> 200 or 302 to `/swagger-ui/index.html` (follow and assert 200); `GET /swagger-ui/index.html` -> 200; `GET /actuator/health` anonymous -> 200 with `"status":"UP"`; `GET /actuator/env` -> 404; form login with `staff / staff123` (`formLogin()` from spring-security-test) -> 302 to `/staff`; wrong password -> `/login?error`; `GET /login` renders the demo credentials text "manager123"; CSP header present on `/login`; `GET /robots.txt` contains `Disallow: /staff/`.
  - `ErrorPagesTest`: `GET /nope` -> 404 page containing "Request id"; a `RequestPostProcessor` that forces a 500 from a test-only endpoint is not required; assert the 403 page renders for STAFF on `/staff/employees` with the text "Managers only".
- [ ] **Step 2: Run**; red. **Step 3: Implement.** Templates are plain HTML using a minimal `layout.html` fragment (`<html th:fragment="layout(title, content)">`) so Task 9 can restyle without touching controllers. **Step 4: Run** all tests; green.
- [ ] **Step 5: Commit** `feat(security): form login, roles, CSRF, CSP and designed error pages`

---

### Task 8: Operations: request logging, rate limiting, reset and about

**Files:**
- Create: `ops/RequestLoggingFilter.java`, `ops/RateLimiter.java`, `ops/RateLimitFilter.java`, `ops/ResetController.java`, `ops/ResetScheduler.java`, `service/ResetService.java`, `service/AboutService.java`, `web/AboutController.java`, `templates/about.html` (content only; styled in Task 9)
- Test: `ops/RequestLoggingFilterTest.java`, `ops/RateLimiterTest.java`, `ops/RateLimitFilterTest.java`, `ops/ResetEndpointTest.java`, `service/ResetServiceTest.java`, `web/AboutPageTest.java`

**Interfaces:**
```java
public class RequestLoggingFilter extends OncePerRequestFilter { /* header X-Request-Id (accept incoming if it matches [A-Za-z0-9-]{8,64}, else UUID); MDC keys request_id, http_method, http_path, http_status, duration_ms, principal, client_ip; log.info("request {} {} -> {} in {} ms", ...) ; skips /static, /css, /js, /fonts, /favicon.svg, /robots.txt */ }
public class RateLimiter { public RateLimiter(int perHour, Clock clock); public boolean tryAcquire(String key); /* fixed window of one hour per key; map bounded at 10_000 entries (drop oldest window on overflow); expired windows removed on access */ }
public class RateLimitFilter extends OncePerRequestFilter { /* enabled by InnkeeperProperties; applies to POST on /book, /complaints, /my-bookings, /api/bookings, /api/bookings/lookup, /api/bookings/*/cancel, /api/demo/race; key = first X-Forwarded-For hop else remoteAddr; on limit: 429 with text/html page for page paths (forward to /error/429) or application/problem+json for /api */ }
public class ResetService {
    public record Outcome(boolean performed, Instant lastResetAt, String reason) {}
    public Outcome resetIfStale(Duration minimumInterval, String actor); // skips when last_reset_at is within the interval
    public Outcome reset(String actor);                                   // tryLock -> busy Outcome(false, ..., "busy") ; in one transaction: SeedData.deleteAll(); SeedData.seed(); app_metadata last_reset_at = now
    public Optional<Instant> lastResetAt(); public Optional<Instant> seededAt();
}
@RestController public class ResetController { /* POST /internal/reset: 404 when innkeeper.reset-token is blank; 403 when X-Reset-Token missing or mismatched (MessageDigest.isEqual on UTF-8 bytes); 409 when busy; 200 {"performed":true,"lastResetAt":"..."}; optional query force=true bypasses the 30-minute stale check; @Hidden for springdoc */ }
@Component public class ResetScheduler { @Scheduled(cron = "0 0 3 * * *", zone = "America/Chicago") public void nightly() { resetService.resetIfStale(Duration.ofMinutes(30), "scheduler"); } }
public class AboutService { public record Info(String commit, String commitTime, String buildTime, String database, String profile, Optional<Instant> seededAt, Optional<Instant> lastResetAt, String poolStats) {} public Info info(); } // GitProperties and BuildProperties via ObjectProvider; commit falls back to env RENDER_GIT_COMMIT then GIT_COMMIT then "unknown"; database from DataSource metadata "PostgreSQL 16.4" / "H2 2.3.232"; poolStats from HikariDataSource.getHikariPoolMXBean() when the DataSource is Hikari
```

- [ ] **Step 1: Write the failing tests**
  - `RequestLoggingFilterTest`: a MockMvc `GET /login` response carries `X-Request-Id`; an incoming valid id is echoed; an invalid one is replaced; a `ListAppender` on the filter's logger captures one event whose MDC has `http_path` `/login` and `http_status` `200` and no query string when `GET /login?next=1` is requested.
  - `RateLimiterTest`: 20 acquisitions succeed, the 21st fails, after the clock advances one hour it succeeds; 10,001 distinct keys keep the map at 10,000.
  - `RateLimitFilterTest` (`@TestPropertySource(properties = "innkeeper.rate-limit.enabled=true")`): 21st `POST /api/bookings/lookup` from `X-Forwarded-For: 203.0.113.9, 10.0.0.1` returns 429 with `application/problem+json`; a different first hop is not limited; `GET` is never limited; 21st `POST /my-bookings` returns 429 HTML containing "Too many requests".
  - `ResetEndpointTest`: with the test token: missing header 403, wrong 403, right 200 and `performed` true; with `innkeeper.reset-token=` (a nested test class with `@TestPropertySource`) 404; CSRF is not required (no token in the request).
  - `ResetServiceTest`: after creating an extra booking, `reset` restores `SeedData.expectedCounts()` and updates `last_reset_at`; `resetIfStale(30 min)` right after is skipped with reason "recent"; two concurrent `reset` calls (barrier) yield one performed and one "busy"; a code unlocked before the reset no longer resolves (`requireByCode` throws `NotFoundException`).
  - `AboutPageTest`: `GET /about` renders "H2" and the active profile "test" and the seed time; with `GitProperties` absent it renders "unknown" (the test profile has no git.properties; confirm by asserting the text).
- [ ] **Step 2: Run**; red. **Step 3: Implement.** Register both filters as `FilterRegistrationBean`s with explicit order (logging first, then rate limit, both before Spring Security using `Ordered.HIGHEST_PRECEDENCE + 10/20`).
- [ ] **Step 4: Run** all tests; green. **Step 5: Commit** `feat(ops): request ids and structured request log, rate limiting, nightly reset and about page`

---

### Task 9: Design foundation: layout, tokens, fonts, theme, header and footer

**Files:**
- Create: `static/css/innkeeper.css`, `static/css/print.css`, `static/js/theme.js`, `static/js/copy.js`, `static/favicon.svg`, `templates/layout.html` (replace), `templates/fragments/header.html`, `fragments/footer.html`, `fragments/forms.html` (field + error macros), `fragments/chips.html`; restyle `templates/login.html`, `templates/about.html`, `templates/error/*.html`; `config/WebConfig.java` (static resource cache control 7 days, `ZoneId`/date formatter helper bean `Dates` for templates: `format(LocalDate)` -> "Fri 3 Oct 2026", `money(BigDecimal)` -> "$1,234.50")
- Test: `web/LayoutTest.java`

**Requirements (from `docs/DESIGN.md` section 10 and the design review rulings; read the frontend-design skill at `C:\Users\sahaj\.claude\plugins\cache\claude-plugins-official\frontend-design\3deb821cb71c\skills\frontend-design\SKILL.md` before writing any HTML or CSS):**
- `@font-face` for Fraunces (variable, `font-weight: 300 900`, `font-optical-sizing: auto`, normal and italic) and Source Sans 3 (`200 900`, normal and italic) from `/fonts/*.woff2` with `font-display: swap`; fallbacks Georgia / system sans.
- CSS custom properties for every token in the table (light on `:root`, dark under `[data-theme="dark"]` and under `@media (prefers-color-scheme: dark)` guarded by `:root:not([data-theme="light"])`); `color-scheme` set accordingly.
- `theme.js` loaded in `<head>` (tiny, no inline script): reads `localStorage.theme`, sets `data-theme` before paint; a header toggle button (`aria-pressed`, label "Dark theme" / "Light theme") flips and stores it.
- Layout: demo strip above the header (copy from DESIGN.md section 4), header with wordmark "Innkeeper" in Fraunces, nav (public: Rooms, Find my booking, Complaints, About; staff when authenticated: Desk, Bookings, Maintenance, Inventory, Complaints, Employees for managers, Sign out; "Staff sign in" otherwise) using `sec:authorize`; `<main id="main">` with a "Skip to content" link; footer with the under-the-hood line (commit, database, last reset; fed by `AboutService` through a `@ControllerAdvice` model attribute `underTheHood`).
- Components: `.ledger` (ruled rows), `.panel` (mist background, 4 px radius, no shadow), buttons (`.btn`, `.btn-primary`, `.btn-outline`, `.btn-danger`), chips (`.chip-confirmed` etc.), form fields with `.field-error` and a `.form-errors` summary with `tabindex="-1"` that receives focus when present (handled by a few lines in `copy.js`? No: keep a separate `static/js/focus-errors.js` included on form pages), flash line (`.flash`), tables that stack under 640 px (`data-label` cells), `.strip` styles (Task 11 uses them), focus ring 2 px walnut/brass with 2 px offset, `@media (prefers-reduced-motion: reduce)` removing transitions, print stylesheet forcing light tokens and hiding chrome.
- `copy.js`: any `[data-copy]` button copies its target's text and swaps its label to "Copied" for two seconds.
- Type scale from 16 px at 1.25; body measure 64 rem on reading pages (`.measure`), 88 rem for staff (`.wide`).

- [ ] **Step 1: Write the failing test** `LayoutTest`: `GET /login` contains `<link rel="stylesheet" href="/css/innkeeper.css">`, `/js/theme.js`, the demo strip text "Resets nightly", the skip link, no `<script>` without `src`, no `style=` attribute; `GET /css/innkeeper.css` is 200 and contains `--paper:#F7F3EC` (or the equivalent declaration) and `@font-face`; `GET /fonts/fraunces.woff2` is 200 with `font/woff2`; `GET /about` shows the footer "Under the hood".
- [ ] **Step 2: Run**; red. **Step 3: Implement**, then take a look: run `mvn -q -B spring-boot:run -Dspring-boot.run.profiles=demo` on port 8080 in the background, open `/login`, `/about`, `/nope` with the headless browser (`~/.claude/skills/gstack/browse/dist/browse` with `BROWSE_SERVER_PORT=39281`: `goto`, `viewport 1280x800`, `screenshot`, `viewport 400x800`, `screenshot`, then set `localStorage.theme=dark` with `storage set theme dark`, reload, screenshot) and fix what looks wrong; stop the server.
- [ ] **Step 4: Run** all tests; green. **Step 5: Commit** `feat(design): layout, tokens, self-hosted type, theme toggle and print styles`

---

### Task 10: JSON API, OpenAPI and the race demo

**Files:**
- Create: `config/OpenApiConfig.java`, `api/dto/*.java`, `api/ApiExceptionHandler.java`, `api/BranchApiController.java`, `api/RoomApiController.java`, `api/BookingApiController.java`, `api/DemoApiController.java`, `service/RaceDemoService.java`
- Test: `api/BranchApiTest.java`, `api/RoomApiTest.java`, `api/BookingApiTest.java`, `api/DemoApiTest.java`, `api/OpenApiTest.java`

**Interfaces:**
```java
record BranchDto(Long id, String code, String name, String tagline, String city, String state, BigDecimal fromRate, String timezone) {}
record RoomTypeDto(String code, String name, int maxOccupancy, String bedSetup) {}
record RoomDto(Long id, Long branchId, String branchCode, String branchName, String roomNumber, int floor, RoomTypeDto type, BigDecimal nightlyRate, String status) {}
record NightDto(LocalDate date, boolean available) {}
record RoomAvailabilityDto(Long roomId, LocalDate from, int days, List<NightDto> nights) {}
record CreateBookingRequest(@NotNull Long roomId, @NotNull LocalDate checkIn, @NotNull LocalDate checkOut, @Min(1) @Max(8) int adults, @Min(0) @Max(8) int children,
        @NotBlank @Size(max = 80) String firstName, @NotBlank @Size(max = 80) String lastName, @NotBlank @Email @Size(max = 254) String email, @Size(max = 32) String phone, @Size(max = 500) String specialRequests) {}
record GuestDto(String firstName, String lastName, String email) {}
record InvoiceLineDto(String kind, String description, int quantity, BigDecimal unitAmount, BigDecimal amount) {}
record InvoiceDto(BigDecimal taxRate, BigDecimal roomSubtotal, BigDecimal tax, BigDecimal fines, BigDecimal cancellationFee, BigDecimal total, BigDecimal balance, String status, List<InvoiceLineDto> lines) {}
record BookingDto(String code, String status, RoomDto room, LocalDate checkIn, LocalDate checkOut, long nights, int adults, int children, GuestDto guest, String specialRequests, InvoiceDto invoice, BigDecimal cancellationFeeNow, Instant cancellationDeadline, Instant createdAt) {}
record LookupRequest(@NotBlank @Pattern(regexp = "INN-[A-Z2-9]{6}") String code, @NotBlank @Email String email) {}
record CancelRequest(@NotBlank @Email String email) {}
record CancelResponse(String code, String status, BigDecimal cancellationFee) {}
record RaceResult(int attempts, int created, int rejected, String winnerCode, String room, LocalDate night, long durationMs) {}
```
Endpoints exactly as `docs/DESIGN.md` section 5 (`/api/availability` does not exist). `ApiExceptionHandler` (`@RestControllerAdvice(basePackages = "...api")`) returns `ProblemDetail`: 400 with `errors` map for `MethodArgumentNotValidException` / `BookingRuleException` (field -> message) and for unparseable dates; 404 for `NotFoundException`; 409 for `RoomUnavailableException`, `IllegalBookingStateException`, optimistic locking; 429 is produced by the filter. `RaceDemoService.run()`: picks the first AVAILABLE room of the DEN branch and the first free night at least 200 days ahead, fires 10 `bookingService.create` calls on a 10-thread pool with a `CyclicBarrier`, counts created (exactly 1 expected) and `RoomUnavailableException`s, cancels the winner (free, so no fee), returns `RaceResult`. `OpenApiConfig` sets title "Innkeeper API", version from `BuildProperties` when present, description with the demo note.

- [ ] **Step 1: Write the failing tests** (MockMvc, anonymous unless stated): branches returns 3 with `fromRate` 89.00 for DEN; rooms with `checkIn`/`checkOut` in a booked range excludes the booked room and with `guests=4` returns only suites; `GET /api/rooms/{id}/availability?days=200` clamps to 90 nights; `days=0` -> 400; unknown room -> 404 problem; `POST /api/bookings` valid -> 201 with `Location: /api/bookings/{code}`... (there is no GET by code; set `Location` to `/bookings/{code}` page) and a code matching the pattern, invoice total = nights x rate x (1 + tax) rounded; invalid body -> 400 with `errors.checkOut` for check-out before check-in and `errors.email` for a bad email; same room and dates twice -> 409 problem with title "Room unavailable"; `POST /api/bookings/lookup` right pair -> 200 with `cancellationFeeNow`; wrong email -> 404; malformed code -> 400; `POST /api/bookings/{code}/cancel` right email -> 200 `cancellationFee` 0 and status CANCELLED, second call -> 409, wrong email -> 404; `POST /api/demo/race` -> 200 with `created` 1 and `rejected` 9 and the winner's booking is CANCELLED afterwards; `OpenApiTest`: `GET /api/openapi` lists paths `/api/bookings`, `/api/bookings/lookup`, `/api/rooms/{id}/availability`, `/api/demo/race` and not `/internal/reset`.
- [ ] **Step 2: Run**; red. **Step 3: Implement.** **Step 4: Run** all; green. **Step 5: Commit** `feat(api): rooms, availability, bookings and race demo with OpenAPI at /api/docs`

---

### Task 11: Public pages

**Files:**
- Create: `web/BookingAccess.java` (session set of unlocked codes; `unlock(code)`, `canView(code, authentication, booking)` true for unlocked, staff roles, or a GUEST whose display name equals the guest email), `web/PublicPagesController.java` (`/`, `/rooms`, `/rooms/{id}`), `web/BookingPagesController.java` (`/book` GET+POST, `/bookings/{code}`, `/bookings/{code}/cancel` GET+POST, `/bookings/{code}/invoice`), `web/MyBookingsController.java` (`/my-bookings` GET+POST), `web/ComplaintController.java` (`/complaints/new` GET+POST, `/complaints/{ticket}` thanks page), `web/form/SearchForm.java`, `BookingForm.java`, `LookupForm.java`, `ComplaintForm.java`, templates `index.html`, `rooms.html`, `room.html`, `book.html`, `booking.html`, `booking-cancel.html`, `invoice.html`, `my-bookings.html`, `complaint.html`, `complaint-filed.html`, `static/js/strip.js`, `static/js/race.js`, `static/js/focus-errors.js`
- Test: `web/PublicPagesTest.java`, `web/BookingFlowTest.java`, `web/MyBookingsTest.java`, `web/ComplaintPagesTest.java`

**Requirements:** exactly the page contents and states in `docs/DESIGN.md` section 4 and the state table in `docs/REVIEW.md`. Dates carry as `checkIn`, `checkOut`, `guests` query parameters on every room link and on the "Book this room" link. The strip is server-rendered: `<div class="strip" role="grid" aria-label="Availability for the next 60 days">` with week rows (`role="row"`) of `<button type="button" role="gridcell" class="night" data-date="2026-10-03" data-available="true" aria-label="Fri 3 Oct, available" tabindex="-1">3</button>`; booked nights get `data-available="false"`, `aria-disabled="true"`, a hatched background; `strip.js` implements roving tabindex (arrows, Home, End), Enter/Space to set check-in then check-out (all nights in between must be available; otherwise announce "Those dates include a booked night"), updates a live region `#strip-status` ("3 nights, $267.00") and the hidden `checkIn`/`checkOut` inputs of the book form; without JavaScript each cell is wrapped in a link to `/book?roomId=&checkIn=<date>&checkOut=<date+1>`. `race.js` posts to `/api/demo/race` and renders the result sentence "10 visitors tried to book room 101 for Fri 3 Apr 2027 at the same instant: 1 booking created, 9 rejected by the database" plus the winner code. Booking POST: validate form (Bean Validation + `StayPeriod`/`OccupancyRule` errors mapped to fields), honeypot field `website` (non-empty -> redirect to a fake confirmation? No: redirect to `/` with no message), create, unlock the code in the session, redirect to `/bookings/{code}`. `/bookings/{code}` when not viewable -> redirect to `/my-bookings?code={code}` with the flash "Enter the email used for the booking to open it."; unknown code after a reset -> same redirect with "We couldn't find that booking." Cancel GET shows the fee sentence; POST cancels and flashes "Booking cancelled." with the fee if any. Invoice page uses `print.css` and a "Print" button (`window.print` from `copy.js`? No: a tiny `static/js/print.js`).

- [ ] **Step 1: Write the failing tests** (MockMvc with sessions):
  - Landing renders three branch cards, the search form with `min` attributes on the date inputs, the demo strip and the race button; `GET /?checkIn=bad` -> the form error "Enter a date".
  - `/rooms?checkIn=D+5&checkOut=D+7&guests=2` lists rooms with per-stay totals and excludes a room booked for those dates; with `guests=9` -> validation message; no results renders the "Nothing free" sentence.
  - `/rooms/{id}` renders 60 `.night` cells, marks the seeded booked nights `data-available="false"`, pre-selects `?checkIn&checkOut`, shows "Not available" with the next opening when the requested dates are blocked; unknown id -> 404 page.
  - Booking flow: `GET /book?roomId&checkIn&checkOut&guests` shows the summary with the total and the policy line; `POST /book` with a missing last name re-renders with the inline error and the summary; valid POST -> 302 to `/bookings/INN-...`; following it (same session) shows the code, the email and "Cancel booking"; a new session on the same URL -> 302 to `/my-bookings?code=...`; POST the room again for the same dates -> 200 with the "was just taken" banner; `GET /bookings/{code}/cancel` shows "Cancelling now is free."; `POST` -> 302 back, page shows chip "Cancelled"; honeypot filled -> 302 to `/` and no booking created; invoice page contains the line table and the `print.css` link.
  - `/my-bookings`: wrong pair -> "We couldn't find a booking with that code and email."; right pair -> 302 to the booking page and it opens; logged in as `guest` (`formLogin` or `@WithMockUser(username="guest", roles="GUEST")` with display name `guest@example.com` loaded through the real `UserDetailsService`: use `with(user(...))`) lists 2 bookings.
  - Complaint: GET renders the branch select; POST without a description -> inline error; with an unmatched code + email -> the field error; valid -> 302 to `/complaints/CMP-...` showing the ticket.
  - Static: `/js/strip.js`, `/js/race.js` are 200.
- [ ] **Step 2: Run**; red. **Step 3: Implement**, then look at every page with the headless browser at 1280 and 400 px (light and dark) and fix layout problems before finishing. **Step 4: Run** all tests; green.
- [ ] **Step 5: Commit** `feat(pages): landing, rooms, room strip, booking flow, lookup and complaints`

---

### Task 12: Staff pages

**Files:**
- Create: `web/StaffDeskController.java` (`/staff`, `POST /staff/reset` manager), `web/StaffBookingController.java` (`/staff/bookings`, `/staff/bookings/{code}`, POST `check-in`, `check-out`, `fines`, `payments`), `web/MaintenanceController.java` (`/staff/maintenance`, POST create, `{id}/start`, `{id}/done`, `{id}/team`, `rooms/{roomId}/return`), `web/InventoryController.java` (`/staff/inventory`, POST `{id}/adjust`, POST create), `web/StaffComplaintController.java` (`/staff/complaints`, POST `{id}/start`, `{id}/resolve`), `web/EmployeeController.java` (`/staff/employees`, POST create, `{id}/deactivate`), forms, templates `staff/desk.html`, `bookings.html`, `booking.html`, `maintenance.html`, `inventory.html`, `complaints.html`, `employees.html`, `web/Masking.java` (`email("grace.example@example.com")` -> `g***@example.com`, `phone` -> `***`)
- Test: `web/StaffDeskPageTest.java`, `StaffBookingPagesTest.java`, `MaintenancePagesTest.java`, `InventoryPagesTest.java`, `StaffComplaintPagesTest.java`, `EmployeePagesTest.java`

**Requirements:** page contents from `docs/DESIGN.md` section 4 (dashboard order: branch switcher, departures ledger, arrivals ledger, then occupancy, maintenance, low stock, complaints; STAFF default to their employee's branch, MANAGER to all); every mutation POST + redirect + flash; masked guest emails and phones everywhere on staff pages; "Reset demo data" button (MANAGER) with a confirm sentence, POST `/staff/reset` calls `resetService.reset(username)` and flashes the outcome; maintenance board stacks under 720 px; tables stack under 640 px; the booking page shows actions only for the current state and lists invoice, payments, fines and events.

- [ ] **Step 1: Write the failing tests** (MockMvc with `@WithMockUser(username = "staff", roles = "STAFF")` and `manager`/`MANAGER`, CSRF on POSTs):
  - Desk as staff renders "Departures today (4)" then "Arrivals today (3)" (assert order by index in the body), the DEN occupancy line, "Low stock", the masked email `g***@example.com` and no `@example.com` address unmasked; as manager renders the reset button; as staff the reset button is absent and `POST /staff/reset` -> 403; anonymous -> 302.
  - Booking pages: search by "Otto" lists the arrival; the booking page shows "Check in"; `POST check-in` with a free space -> 302 and the page shows "Checked in" chip and the space; `POST check-out` -> 302, page shows "Record payment" with the balance prefilled; `POST payments` equal to the balance -> chip "Paid"; `POST fines` on a checked-out booking -> 302 with the error flash; a wrong-state action renders the 409 message, not a 500.
  - Maintenance: board shows the seeded URGENT card first in Open; create with out-of-service on DEN 101 (has stays) -> error flash "has upcoming stays"; create on a free room -> the room appears under "Out of service"; `POST {id}/done` returns it.
  - Inventory: adjust below zero -> error flash; adjust -5 -> new quantity shown; low-stock row has class `low`.
  - Complaints: resolve without a note -> error; with a note -> disappears from the open list.
  - Employees: STAFF -> 403 page "Managers only"; MANAGER adds and deactivates; duplicate email -> inline error.
- [ ] **Step 2: Run**; red. **Step 3: Implement**, then look at every staff page at 1280 and 400 px in both themes with the headless browser (log in through the form) and fix problems. **Step 4: Run** all tests; green.
- [ ] **Step 5: Commit** `feat(staff): desk, bookings, maintenance, inventory, complaints and employees pages`

---

### Task 13: Design QA pass and screenshots

**Files:**
- Create: `docs/screenshots/<page>-<width>-<theme>.png` for landing, rooms, room, book, booking, invoice, my-bookings, complaint, login, about, staff-desk, staff-booking, maintenance, inventory, employees, 404 (16 pages x 1280/400 x light/dark = 64 files; keep each under 300 KB by using `screenshot --viewport`), `docs/screenshots/README.md` listing them
- Modify: CSS and templates as needed

- [ ] **Step 1:** Run the app on the `demo` profile (fresh `./data`), open every page with the headless browser at 1280x800 and 400x800 in light and dark, capture the console after each page (`console --errors` must be empty), check focus visibility with Tab presses (`press Tab` then `screenshot`), and record each defect found (spacing, hierarchy, contrast, overflow, unstyled state, copy) in `docs/screenshots/README.md` before fixing it.
- [ ] **Step 2:** Fix defects in CSS/templates; re-run the affected MockMvc tests; re-shoot.
- [ ] **Step 3:** Commit `chore(design): screenshot pass at 1280 and 400 px, light and dark, with fixes`

---

### Task 14: Docker, render.yaml, CI, deploy, nightly reset and keep-warm workflows

**Files:**
- Create: `Dockerfile`, `.dockerignore`, `render.yaml`, `.github/workflows/ci.yml`, `.github/workflows/nightly-reset.yml`, `.github/workflows/keep-warm.yml`, `.github/dependabot.yml`, `scripts/smoke.sh`
- Test: `scripts/smoke.sh` is exercised locally against the Docker container

**Requirements:**
- Dockerfile: stage 1 `maven:3.9-eclipse-temurin-21` copies `pom.xml`, runs `mvn -q -B dependency:go-offline`, copies `src` and `.git` (for the git plugin; `.dockerignore` excludes `target`, `data`, `docs/screenshots`, `.superpowers`), runs `mvn -q -B -DskipTests package`, extracts layers with `java -Djarmode=tools -jar app.jar extract --layers --destination extracted`; stage 2 `eclipse-temurin:21-jre` as non-root user `innkeeper`, copies the layers, creates `/app/data` owned by the user, runs a training run `java -XX:ArchiveClassesAtExit=/app/app.jsa -Dspring.context.exit=onRefresh -Dspring.profiles.active=demo -Dspring.datasource.url=jdbc:h2:mem:cds;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE -jar ...` (must succeed), then `ENV JAVA_TOOL_OPTIONS="-XX:SharedArchiveFile=/app/app.jsa -XX:TieredStopAtLevel=1 -XX:+UseSerialGC -XX:MaxRAMPercentage=60 -Xss512k"`, `ENV SPRING_PROFILES_ACTIVE=demo`, `EXPOSE 8080`, `HEALTHCHECK` on `/actuator/health/liveness`, `ENTRYPOINT ["java", "-jar", "/app/app.jar"]` (or the launcher for the layered layout).
- `render.yaml`: one `web` service `innkeeper`, `runtime: docker`, `plan: free`, `region: oregon`, `healthCheckPath: /actuator/health/liveness`, `autoDeploy: false`, env vars `SPRING_PROFILES_ACTIVE` (value set by the deploy phase), `INNKEEPER_RESET_TOKEN` (`generateValue: true`), `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD` (`sync: false`).
- `ci.yml`: on `push` (all branches) and `pull_request`; job `test` (ubuntu-latest, temurin 21, Maven cache, `mvn -q -B verify -Dinnkeeper.requireDocker=true`, upload surefire reports on failure); job `docker` (needs test): `docker/build-push-action` builds the image, pushes to `ghcr.io/sahajm99/innkeeper:latest` and `:sha-<short>` only on `push` to `main` (login with `GITHUB_TOKEN`); job `deploy` (needs docker, only `push` to `main`, `if: secrets.RENDER_SERVICE_ID != ''` expressed through an env guard step): `POST https://api.render.com/v1/services/${RENDER_SERVICE_ID}/deploys` with `Authorization: Bearer ${RENDER_API_KEY}`, poll `GET .../deploys/{id}` every 20 s up to 25 minutes until `live` (fail on `build_failed`, `update_failed`, `canceled`, `pre_deploy_failed`), then run `scripts/smoke.sh ${APP_URL}` which: waits for `/actuator/health` 200 (up to 5 min), creates a booking through `POST /api/bookings` for room id from `GET /api/rooms?branchId=1` and dates 100 days ahead, looks it up, cancels it, asserts `CANCELLED`.
- `nightly-reset.yml`: `schedule: cron "30 9 * * *"` and `workflow_dispatch`; steps: poll health until 200 (up to 5 min), `curl --fail --retry 5 --retry-all-errors --max-time 180 -X POST -H "X-Reset-Token: ${{ secrets.INNKEEPER_RESET_TOKEN }}" "$APP_URL/internal/reset"`, then `GET /api/branches` must return 3 entries.
- `keep-warm.yml`: `cron "*/10 13-23,0-5 * * *"` hitting `/actuator/health`, `continue-on-error: true`.
- `dependabot.yml`: maven and github-actions, monthly.
- `APP_URL` comes from repository variable `vars.APP_URL`; secrets `RENDER_API_KEY`, `RENDER_SERVICE_ID`, `INNKEEPER_RESET_TOKEN`.

- [ ] **Step 1:** Write `scripts/smoke.sh` and the workflows; write the Dockerfile.
- [ ] **Step 2:** `docker build -t innkeeper:local .` succeeds (training run included); `docker run --rm -p 8080:8080 innkeeper:local` (if port 8080 is blocked on this Windows host, use `-p 18080:8080`) serves `/actuator/health` UP within 90 s, `/` renders, `/about` shows the commit; `scripts/smoke.sh http://localhost:18080` passes; record the container's RSS from `docker stats --no-stream` in the report.
- [ ] **Step 3:** Commit `chore(ci): Dockerfile with AppCDS, GitHub Actions build/test/deploy, nightly reset and keep-warm`

---

### Task 15: README and ADRs

**Files:**
- Create: `README.md`, `docs/adr/0001-room-night-ledger.md`, `0002-h2-for-tests-postgres-in-ci.md`, `0003-nightly-reset-strategy.md`, `0004-session-unlock-not-tokens.md`
- Modify: `docs/PROGRESS.md`

**README contents, in order:** title and one-sentence origin credit ("Innkeeper started as the CSCE 5350 Fundamentals of Database Systems group project at UNT (Fall 2024, Group 15); the code, schema and application here are a new implementation."), CI badge (`https://github.com/sahajm99/innkeeper/actions/workflows/ci.yml/badge.svg`), live URL line with the sleep note and the keep-warm hours, "Try it in 30 seconds" click path (search, book, copy the code, cancel; staff login with the printed credentials; the race button), "The interesting part" (the `room_night` unique constraint linked to `BookingRaceTest` and `PostgresBookingRaceTest`), architecture diagram (ASCII), run locally (`docker run --rm -p 8080:8080 ghcr.io/sahajm99/innkeeper:latest` and `mvn -q spring-boot:run`), profiles table, environment variables table (from `.env.example`), API section with `/api/docs`, tests section (counts by kind, how to run, Docker note for the PostgreSQL tests), hosting layout (Render, Neon or demo, what sleeps, what resets when, the GitHub schedule caveat and how to re-enable), what changed versus the course design (link to `docs/SCHEMA-CHANGES.md` and the ADRs), screenshots (four thumbnails), licence.

- [ ] **Step 1:** Write the README and the four ADRs (context, decision, consequences, alternatives).
- [ ] **Step 2:** Verify every command in the README runs as written (Maven run, Docker run, curl examples) and every relative link resolves.
- [ ] **Step 3:** Commit `docs: README with origin credit, run instructions, hosting layout and ADRs`

---

### Task 16: Repository, hosting, portfolio and live QA (controller-run)

- [ ] Create the public repo `sahajm99/innkeeper` with `gh repo create --public --source . --push` while `gh auth status` shows `sahajm99` active; confirm CI green.
- [ ] Hosting per `docs/DESIGN.md` section 7 with the credentials from the author; set `gh secret set RENDER_API_KEY`, `RENDER_SERVICE_ID`, `INNKEEPER_RESET_TOKEN` and `gh variable set APP_URL`; trigger the deploy; confirm `live`, health, smoke.
- [ ] Portfolio entry in `portfolio-next/src/data/projects.ts` following the `cardiolens` shape, committed alone as described in the mission, pushed; Vercel deployment READY.
- [ ] Live QA with the headless browser on the public URL: book, invoice, cancel; staff login, check a guest in; `/actuator/health`; console errors on every page; record in `docs/PROGRESS.md` and the final report.
