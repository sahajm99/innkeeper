package io.github.sahajm99.innkeeper.seed;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import io.github.sahajm99.innkeeper.domain.BookingStatus;
import io.github.sahajm99.innkeeper.domain.BranchDates;
import io.github.sahajm99.innkeeper.domain.ConfirmationCodes;
import io.github.sahajm99.innkeeper.domain.InvoiceCalculator;
import io.github.sahajm99.innkeeper.domain.InvoiceCalculator.FineLine;
import io.github.sahajm99.innkeeper.domain.InvoiceCalculator.Line;
import io.github.sahajm99.innkeeper.model.AppMetadata;
import io.github.sahajm99.innkeeper.model.BookingEventType;
import io.github.sahajm99.innkeeper.model.ComplaintCategory;
import io.github.sahajm99.innkeeper.model.ComplaintStatus;
import io.github.sahajm99.innkeeper.model.InvoiceStatus;
import io.github.sahajm99.innkeeper.model.MaintenanceStatus;
import io.github.sahajm99.innkeeper.model.ParkingKind;
import io.github.sahajm99.innkeeper.model.PaymentMethod;
import io.github.sahajm99.innkeeper.model.Priority;
import io.github.sahajm99.innkeeper.model.RoomStatus;

import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

/**
 * The demo data set: three fictional Texas branches and everything hanging off them, with the
 * stays placed relative to today so the dashboard always has arrivals, departures, an overstay and
 * a run of past invoices.
 *
 * <p>Two callers share this class: the {@code V2__seed_data} Flyway migration, which fills a fresh
 * database, and the nightly reset, which calls {@link #deleteAll()} and then {@link #seed()}
 * again. Everything is inserted with plain JDBC and generated ids - no explicit primary keys, so
 * the identity sequences stay in step - and every invoice is computed by {@link InvoiceCalculator}
 * rather than typed out, so the seed cannot drift from the domain rules.</p>
 *
 * <p>Nothing here is real: every name is a placeholder, every email is on {@code example.com},
 * guests carry no phone number at all and the branch phone numbers are obvious dummies.</p>
 *
 * <p>An instance holds the ids of the rows it is writing while {@link #seed()} runs, which clears
 * them first, so one instance can seed repeatedly but must not be shared between threads.</p>
 */
public class SeedData {

    /**
     * Every table, children before parents, so deleting in this order never trips a foreign key
     * even on a database without cascading deletes configured for the statement.
     */
    public static final List<String> TABLES_CHILD_FIRST = List.of("booking_event", "payment",
        "invoice_line", "invoice", "fine", "room_night", "parking_space", "complaint", "booking",
        "guest", "maintenance_request", "maintenance_team", "inventory_item", "user_account",
        "employee", "room", "room_type", "branch", "app_metadata");

    /** The row counts {@link #seed()} inserts, so tests can assert the whole set in one go. */
    public record Counts(int branches, int roomTypes, int rooms, int guests, int bookings,
        int roomNights, int invoices, int employees, int accounts, int teams, int requests,
        int inventoryItems, int parkingSpaces, int complaints) {
    }

    // --- fixed points -----------------------------------------------------------------------

    /** Every branch runs on Central time, so "today" is the same calendar day for all of them. */
    private static final ZoneId ZONE = ZoneId.of("America/Chicago");

    private static final String EXAMPLE_COM = "@example.com";
    private static final String ACTOR = "seed";
    private static final String STATE = "TX";
    private static final int CODE_SEED = 42;
    private static final int TICKET_BODY_LENGTH = 6;
    private static final int MAX_CODE_ATTEMPTS = 100;

    private static final LocalTime BOOKED_AT = LocalTime.of(10, 15);
    private static final LocalTime CHECK_IN_AT = LocalTime.of(15, 0);
    private static final LocalTime CHECK_OUT_AT = LocalTime.of(11, 0);
    private static final LocalTime CANCELLED_AT = LocalTime.of(9, 20);
    private static final LocalTime FINE_AT = LocalTime.of(21, 0);
    private static final LocalTime REPORTED_AT = LocalTime.of(8, 0);
    private static final LocalTime STARTED_AT = LocalTime.of(9, 30);
    private static final LocalTime COMPLETED_AT = LocalTime.of(16, 0);
    private static final LocalTime COMPLAINED_AT = LocalTime.of(19, 45);

    /** The stay the Denton complaint is attached to; keyed the way {@link Stay#key()} is. */
    private static final String COMPLAINT_STAY = "Grace Example DEN-101";

    /** The Denton room held out of service, which is also the one with an urgent repair open. */
    private static final String OUT_OF_SERVICE = "DEN-106";

    /** Who records payments and issues fines at each branch. */
    private static final Map<String, String> FRONT_DESK =
        Map.of("DEN", "Ben Sample", "FTW", "Cleo Placeholder", "AUS", "Finn Mockup");

    // --- the data itself ---------------------------------------------------------------------

    private record BranchSpec(String code, String name, String tagline, String description,
        String addressLine, String city, String postalCode, String phone, String taxRate) {

        String email() {
            return city.toLowerCase(Locale.ROOT).replace(" ", "") + EXAMPLE_COM;
        }
    }

    private record RoomTypeSpec(String code, String name, String description, int maxOccupancy,
        String bedSetup) {
    }

    /** A run of consecutive room numbers on one floor, all of one type and rate. */
    private record RoomBlock(String branch, String type, int floor, String rate, int firstNumber,
        int count) {
    }

    private record EmployeeSpec(String branch, String firstName, String lastName, String position,
        String hiredOn) {

        String fullName() {
            return firstName + " " + lastName;
        }

        String email() {
            return emailFor(firstName, lastName);
        }
    }

    private record TeamSpec(String name, String specialty) {
    }

    private record Supply(String name, String category, String unit, int quantity, int reorderLevel,
        String lowBranch, int lowQuantity) {

        int quantityAt(String branch) {
            return branch.equals(lowBranch) ? lowQuantity : quantity;
        }
    }

    private record ParkingBlock(String branch, int guestSpaces, int staffSpaces) {
    }

    private record Repair(String branch, String room, String title, String description,
        Priority priority, MaintenanceStatus status, String team, String reportedBy, int reportedOn,
        Integer startedOn, Integer completedOn) {
    }

    private record Grievance(String branch, String stayKey, ComplaintCategory category,
        ComplaintStatus status, String description, String guestName, String guestEmail,
        int raisedOn, Integer resolvedOn, String resolutionNote) {
    }

    /** One booking and the guest it belongs to; the dates are offsets in days from today. */
    private record Stay(String firstName, String lastName, String email, String room, int checkIn,
        int checkOut, BookingStatus status, int adults, int children, String specialRequests,
        String fineReason, String fineAmount, String cancellationFee, Integer cancelledOn,
        PaymentMethod paymentMethod) {

        static Stay of(String firstName, String lastName, String room, int checkIn, int checkOut,
                BookingStatus status, int adults, int children) {
            return new Stay(firstName, lastName, emailFor(firstName, lastName), room, checkIn,
                checkOut, status, adults, children, null, null, null, null, null, null);
        }

        Stay withEmail(String address) {
            return new Stay(firstName, lastName, address, room, checkIn, checkOut, status, adults,
                children, specialRequests, fineReason, fineAmount, cancellationFee, cancelledOn,
                paymentMethod);
        }

        Stay requesting(String request) {
            return new Stay(firstName, lastName, email, room, checkIn, checkOut, status, adults,
                children, request, fineReason, fineAmount, cancellationFee, cancelledOn,
                paymentMethod);
        }

        Stay withFine(String reason, String amount) {
            return new Stay(firstName, lastName, email, room, checkIn, checkOut, status, adults,
                children, specialRequests, reason, amount, cancellationFee, cancelledOn,
                paymentMethod);
        }

        Stay cancelled(int day, String fee) {
            return new Stay(firstName, lastName, email, room, checkIn, checkOut, status, adults,
                children, specialRequests, fineReason, fineAmount, fee, day, paymentMethod);
        }

        Stay paidBy(PaymentMethod method) {
            return new Stay(firstName, lastName, email, room, checkIn, checkOut, status, adults,
                children, specialRequests, fineReason, fineAmount, cancellationFee, cancelledOn,
                method);
        }

        String fullName() {
            return firstName + " " + lastName;
        }

        String branch() {
            return room.substring(0, room.indexOf('-'));
        }

        /** Stable within a seed: the demo guest is the only repeat name and books two rooms. */
        String key() {
            return fullName() + " " + room;
        }
    }

    private static final List<BranchSpec> BRANCHES = List.of(
        new BranchSpec("DEN", "Denton Square", "Twelve rooms on the courthouse square",
            "A restored brick building on the courthouse square, a short walk from the record "
                + "shops, the coffee roaster and the university.",
            "101 Example Street", "Denton", "76201", "(000) 000-0100", "0.1300"),
        new BranchSpec("FTW", "Fort Worth Stockyards", "Brick, brass and a quiet courtyard",
            "Brick, brass and a courtyard that stays quiet even on a rodeo weekend, two streets "
                + "back from the stockyards.",
            "202 Example Avenue", "Fort Worth", "76164", "(000) 000-0200", "0.1500"),
        new BranchSpec("AUS", "Austin Lakeline", "A small inn by the water",
            "A small inn by the water at the north edge of the city, a few minutes from the rail "
                + "stop and the trail head.",
            "303 Example Boulevard", "Austin", "78717", "(000) 000-0300", "0.1700"));

    private static final List<RoomTypeSpec> ROOM_TYPES = List.of(
        new RoomTypeSpec("STANDARD", "Standard Queen",
            "A quiet room with one queen bed, a desk by the window and a walk-in shower.",
            2, "One queen bed"),
        new RoomTypeSpec("DELUXE", "Deluxe King",
            "A larger room with one king bed, a sofa and a bath big enough to soak in.",
            3, "One king bed and a sofa"),
        new RoomTypeSpec("SUITE", "Courtyard Suite",
            "A king bed, a separate sitting room and windows that look onto the courtyard.",
            4, "King bed and a separate sitting room"));

    private static final List<RoomBlock> ROOM_BLOCKS = List.of(
        new RoomBlock("DEN", "STANDARD", 1, "89.00", 101, 6),
        new RoomBlock("DEN", "DELUXE", 2, "129.00", 201, 4),
        new RoomBlock("DEN", "SUITE", 3, "189.00", 301, 2),
        new RoomBlock("FTW", "STANDARD", 1, "99.00", 101, 5),
        new RoomBlock("FTW", "DELUXE", 2, "139.00", 201, 3),
        new RoomBlock("FTW", "SUITE", 3, "209.00", 301, 2),
        new RoomBlock("AUS", "STANDARD", 1, "109.00", 101, 5),
        new RoomBlock("AUS", "DELUXE", 2, "149.00", 201, 3),
        new RoomBlock("AUS", "SUITE", 3, "229.00", 301, 2));

    private static final List<EmployeeSpec> EMPLOYEES = List.of(
        new EmployeeSpec("DEN", "Ada", "Example", "Front desk manager", "2023-03-01"),
        new EmployeeSpec("DEN", "Ben", "Sample", "Front desk", "2024-06-15"),
        new EmployeeSpec("FTW", "Cleo", "Placeholder", "Housekeeping lead", "2022-11-07"),
        new EmployeeSpec("FTW", "Dev", "Fixture", "Maintenance", "2023-08-21"),
        new EmployeeSpec("AUS", "Eve", "Specimen", "Front desk", "2025-01-13"),
        new EmployeeSpec("AUS", "Finn", "Mockup", "Night auditor", "2024-02-05"));

    private static final List<TeamSpec> TEAMS = List.of(
        new TeamSpec("Housekeeping", "Rooms and linen"),
        new TeamSpec("Engineering", "Plumbing, electrical, HVAC"),
        new TeamSpec("Grounds", "Courtyard and parking"));

    private static final List<Supply> SUPPLIES = List.of(
        new Supply("Bath towels", "Linen", "each", 120, 60, null, 0),
        new Supply("Queen sheet sets", "Linen", "set", 40, 30, null, 0),
        new Supply("Toilet paper", "Guest supplies", "roll", 60, 24, "DEN", 18),
        new Supply("Coffee pods", "Guest supplies", "each", 300, 100, null, 0),
        new Supply("Shampoo bottles", "Guest supplies", "bottle", 90, 40, "FTW", 25),
        new Supply("Key cards", "Front desk", "each", 80, 50, null, 0));

    private static final List<ParkingBlock> PARKING = List.of(
        new ParkingBlock("DEN", 6, 2),
        new ParkingBlock("FTW", 4, 2),
        new ParkingBlock("AUS", 4, 2));

    private static final List<Repair> REPAIRS = List.of(
        new Repair("DEN", "106", "Leaking bathroom faucet",
            "Water drips from the bathroom faucet with the handle closed; the room is out of "
                + "service until it is fixed.",
            Priority.URGENT, MaintenanceStatus.OPEN, null, "Ben Sample", -2, null, null),
        new Repair("DEN", "203", "Television remote missing",
            "The television remote is not in the room. Housekeeping is checking the laundry.",
            Priority.NORMAL, MaintenanceStatus.IN_PROGRESS, "Housekeeping", "Ada Example",
            -2, -1, null),
        new Repair("FTW", null, "Lobby lamp flickers",
            "The lamp beside the lobby window flickers every few minutes after dark.",
            Priority.LOW, MaintenanceStatus.OPEN, null, "Cleo Placeholder", -3, null, null),
        new Repair("AUS", "203", "Air conditioning not cooling",
            "The air conditioning runs but the room stays warm. The guest was moved for the night.",
            Priority.URGENT, MaintenanceStatus.IN_PROGRESS, "Engineering", "Eve Specimen",
            -1, 0, null),
        new Repair("AUS", null, "Replace hallway carpet",
            "The second floor hallway carpet is worn through by the stairs.",
            Priority.NORMAL, MaintenanceStatus.DONE, "Engineering", "Finn Mockup", -5, -3, -1));

    private static final List<Grievance> GRIEVANCES = List.of(
        new Grievance("DEN", COMPLAINT_STAY, ComplaintCategory.ROOM, ComplaintStatus.OPEN,
            "Heater rattles at night", "Grace Example", "grace.example" + EXAMPLE_COM,
            -1, null, null),
        new Grievance("FTW", null, ComplaintCategory.SERVICE, ComplaintStatus.IN_PROGRESS,
            "Late housekeeping", "Lena Mockup", "lena.mockup" + EXAMPLE_COM, -1, null, null),
        new Grievance("AUS", null, ComplaintCategory.NOISE, ComplaintStatus.RESOLVED,
            "Parking lot noise", "Xavi Demo", "xavi.demo" + EXAMPLE_COM, -4, -3,
            "Moved the guest to a room on the courtyard side for the rest of the stay."));

    private static final List<Stay> STAYS = List.of(
        Stay.of("Grace", "Example", "DEN-101", -2, 1, BookingStatus.CHECKED_IN, 2, 0)
            .requesting("A quiet room if one is free"),
        Stay.of("Hugo", "Sample", "DEN-201", -1, 2, BookingStatus.CHECKED_IN, 2, 1),
        Stay.of("Iris", "Placeholder", "FTW-101", -1, 3, BookingStatus.CHECKED_IN, 1, 0)
            .withFine("Smoking in room", "40.00"),
        Stay.of("Juno", "Fixture", "AUS-301", -2, 1, BookingStatus.CHECKED_IN, 2, 2),
        Stay.of("Kai", "Specimen", "DEN-102", -1, 0, BookingStatus.CHECKED_IN, 1, 0),
        Stay.of("Lena", "Mockup", "FTW-201", -2, 0, BookingStatus.CHECKED_IN, 2, 0),
        Stay.of("Milo", "Testcase", "AUS-101", -3, 0, BookingStatus.CHECKED_IN, 1, 0),
        Stay.of("Nia", "Dummy", "DEN-301", -3, -1, BookingStatus.CHECKED_IN, 2, 2),
        Stay.of("Otto", "Stub", "DEN-103", 0, 2, BookingStatus.CONFIRMED, 2, 0)
            .requesting("Late arrival, after 9 pm"),
        Stay.of("Pia", "Demo", "FTW-102", 0, 1, BookingStatus.CONFIRMED, 2, 0),
        Stay.of("Quinn", "Example", "AUS-201", 0, 3, BookingStatus.CONFIRMED, 2, 1),
        Stay.of("Gus", "Sample", "DEN-202", 3, 5, BookingStatus.CONFIRMED, 2, 0)
            .withEmail("guest" + EXAMPLE_COM).requesting("Ground floor if possible"),
        Stay.of("Gus", "Sample", "AUS-302", 20, 23, BookingStatus.CONFIRMED, 2, 1)
            .withEmail("guest" + EXAMPLE_COM),
        Stay.of("Rae", "Placeholder", "FTW-301", 7, 9, BookingStatus.CONFIRMED, 2, 2)
            .requesting("Two extra pillows"),
        Stay.of("Sol", "Fixture", "DEN-104", 14, 15, BookingStatus.CONFIRMED, 1, 0),
        Stay.of("Tess", "Specimen", "AUS-102", 30, 33, BookingStatus.CONFIRMED, 2, 0),
        Stay.of("Uma", "Mockup", "DEN-105", 40, 42, BookingStatus.CONFIRMED, 2, 0),
        Stay.of("Vic", "Testcase", "DEN-101", -10, -8, BookingStatus.CHECKED_OUT, 1, 0)
            .paidBy(PaymentMethod.CARD),
        Stay.of("Wren", "Dummy", "FTW-103", -9, -6, BookingStatus.CHECKED_OUT, 1, 0)
            .withFine("Lost key card", "25.00").paidBy(PaymentMethod.CASH),
        Stay.of("Xavi", "Demo", "AUS-103", -7, -5, BookingStatus.CHECKED_OUT, 2, 0)
            .paidBy(PaymentMethod.CARD),
        Stay.of("Yara", "Example", "DEN-204", -6, -4, BookingStatus.CHECKED_OUT, 2, 1)
            .paidBy(PaymentMethod.CARD),
        Stay.of("Zed", "Sample", "FTW-202", -5, -3, BookingStatus.CHECKED_OUT, 2, 1)
            .paidBy(PaymentMethod.CASH),
        Stay.of("Ana", "Stub", "AUS-202", 10, 12, BookingStatus.CANCELLED, 2, 0)
            .cancelled(-1, "0.00"),
        Stay.of("Bo", "Fixture", "DEN-302", 1, 3, BookingStatus.CANCELLED, 2, 0)
            .cancelled(0, "189.00"));

    // --- state of one seeding run ---------------------------------------------------------------

    private final JdbcOperations jdbc;
    private final Clock clock;

    private final Map<String, Long> branchIds = new HashMap<>();
    private final Map<String, BigDecimal> branchTaxRates = new HashMap<>();
    private final Map<String, Long> roomTypeIds = new HashMap<>();
    private final Map<String, Long> roomIds = new HashMap<>();
    private final Map<String, BigDecimal> roomRates = new HashMap<>();
    private final Map<String, Long> employeeIds = new HashMap<>();
    private final Map<String, Long> teamIds = new HashMap<>();
    private final Map<String, Long> stayIds = new HashMap<>();
    private final Set<String> usedCodes = new HashSet<>();

    private Random codeSource = new Random(CODE_SEED);

    public SeedData(JdbcOperations jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** The counts {@link #seed()} inserts. Literal on purpose: a drift should fail a test. */
    public static Counts expectedCounts() {
        return new Counts(3, 3, 32, 24, 24, 51, 7, 6, 3, 9, 5, 18, 20, 3);
    }

    /** Inserts the whole demo data set, with every date relative to today in Central time. */
    public void seed() {
        Instant now = clock.instant();
        LocalDate today = BranchDates.today(clock, ZONE);
        reset();

        seedBranches();
        seedRoomTypes();
        seedRooms();
        seedEmployees();
        seedAccounts();
        seedTeams();
        seedRepairs(today);
        seedInventory(now);
        seedParking();
        seedStays(today);
        seedComplaints(today);
        seedMetadata(now);
    }

    /** Empties every table, children first. The nightly reset calls this before re-seeding. */
    public void deleteAll() {
        for (String table : TABLES_CHILD_FIRST) {
            jdbc.update("delete from " + table);
        }
    }

    // --- branches, rooms and staff ----------------------------------------------------------------

    private void seedBranches() {
        for (BranchSpec branch : BRANCHES) {
            BigDecimal taxRate = new BigDecimal(branch.taxRate());
            long id = insert("insert into branch (code, name, tagline, description, address_line, "
                + "city, state, postal_code, phone, email, tax_rate, timezone) "
                + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                branch.code(), branch.name(), branch.tagline(), branch.description(),
                branch.addressLine(), branch.city(), STATE, branch.postalCode(), branch.phone(),
                branch.email(), taxRate, ZONE.getId());
            branchIds.put(branch.code(), id);
            branchTaxRates.put(branch.code(), taxRate);
        }
    }

    private void seedRoomTypes() {
        int sortOrder = 0;
        for (RoomTypeSpec type : ROOM_TYPES) {
            long id = insert("insert into room_type (code, name, description, max_occupancy, "
                + "bed_setup, sort_order) values (?, ?, ?, ?, ?, ?)",
                type.code(), type.name(), type.description(), type.maxOccupancy(), type.bedSetup(),
                ++sortOrder);
            roomTypeIds.put(type.code(), id);
        }
    }

    private void seedRooms() {
        for (RoomBlock block : ROOM_BLOCKS) {
            BigDecimal rate = new BigDecimal(block.rate());
            for (int offset = 0; offset < block.count(); offset++) {
                String number = String.valueOf(block.firstNumber() + offset);
                String key = block.branch() + "-" + number;
                boolean closed = OUT_OF_SERVICE.equals(key);
                long id = insert("insert into room (branch_id, room_type_id, room_number, floor, "
                    + "nightly_rate, status, notes) values (?, ?, ?, ?, ?, ?, ?)",
                    branchIds.get(block.branch()), roomTypeIds.get(block.type()), number,
                    block.floor(), rate,
                    closed ? RoomStatus.OUT_OF_SERVICE.name() : RoomStatus.AVAILABLE.name(),
                    closed ? "Plumbing repair" : null);
                roomIds.put(key, id);
                roomRates.put(key, rate);
            }
        }
    }

    private void seedEmployees() {
        for (EmployeeSpec employee : EMPLOYEES) {
            long id = insert("insert into employee (branch_id, first_name, last_name, email, "
                + "position, hired_on, active) values (?, ?, ?, ?, ?, ?, ?)",
                branchIds.get(employee.branch()), employee.firstName(), employee.lastName(),
                employee.email(), employee.position(), LocalDate.parse(employee.hiredOn()), true);
            employeeIds.put(employee.fullName(), id);
        }
    }

    private void seedAccounts() {
        for (DemoAccounts.Account account : DemoAccounts.ALL) {
            Long employeeId = account.employeeEmail() == null ? null
                : employeeIds.get(account.displayName());
            insert("insert into user_account (username, password_hash, role, display_name, "
                + "employee_id, enabled) values (?, ?, ?, ?, ?, ?)",
                account.username(), DemoAccounts.hashFor(account), account.role(),
                account.displayName(), employeeId, true);
        }
    }

    private void seedTeams() {
        for (BranchSpec branch : BRANCHES) {
            for (TeamSpec team : TEAMS) {
                String contact = branch.code().toLowerCase(Locale.ROOT) + "-"
                    + team.name().toLowerCase(Locale.ROOT) + EXAMPLE_COM;
                long id = insert("insert into maintenance_team (branch_id, name, specialty, "
                    + "contact_email) values (?, ?, ?, ?)",
                    branchIds.get(branch.code()), team.name(), team.specialty(), contact);
                teamIds.put(branch.code() + "-" + team.name(), id);
            }
        }
    }

    private void seedRepairs(LocalDate today) {
        for (Repair repair : REPAIRS) {
            Long roomId = repair.room() == null ? null
                : roomIds.get(repair.branch() + "-" + repair.room());
            Long teamId = repair.team() == null ? null
                : teamIds.get(repair.branch() + "-" + repair.team());
            insert("insert into maintenance_request (branch_id, room_id, title, description, "
                + "priority, status, team_id, reported_by, created_at, started_at, completed_at) "
                + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                branchIds.get(repair.branch()), roomId, repair.title(), repair.description(),
                repair.priority().name(), repair.status().name(), teamId, repair.reportedBy(),
                at(today, repair.reportedOn(), REPORTED_AT),
                at(today, repair.startedOn(), STARTED_AT),
                at(today, repair.completedOn(), COMPLETED_AT));
        }
    }

    private void seedInventory(Instant now) {
        for (BranchSpec branch : BRANCHES) {
            for (Supply supply : SUPPLIES) {
                insert("insert into inventory_item (branch_id, name, category, quantity, "
                    + "reorder_level, unit, updated_at) values (?, ?, ?, ?, ?, ?, ?)",
                    branchIds.get(branch.code()), supply.name(), supply.category(),
                    supply.quantityAt(branch.code()), supply.reorderLevel(), supply.unit(), now);
            }
        }
    }

    private void seedParking() {
        for (ParkingBlock block : PARKING) {
            for (int number = 1; number <= block.guestSpaces(); number++) {
                insertSpace(block.branch(), "P" + number, ParkingKind.GUEST);
            }
            for (int number = 1; number <= block.staffSpaces(); number++) {
                insertSpace(block.branch(), "S" + number, ParkingKind.STAFF);
            }
        }
    }

    private void insertSpace(String branch, String spaceNumber, ParkingKind kind) {
        insert("insert into parking_space (branch_id, space_number, kind) values (?, ?, ?)",
            branchIds.get(branch), spaceNumber, kind.name());
    }

    // --- guests, bookings and money ------------------------------------------------------------

    private void seedStays(LocalDate today) {
        for (Stay stay : STAYS) {
            seedStay(stay, today);
        }
    }

    private void seedStay(Stay stay, LocalDate today) {
        LocalDate checkIn = today.plusDays(stay.checkIn());
        LocalDate checkOut = today.plusDays(stay.checkOut());
        LocalDate bookedOn = earlier(checkIn.minusDays(5), today.minusDays(2));
        Instant createdAt = at(bookedOn, BOOKED_AT);

        long guestId = insert("insert into guest (first_name, last_name, email, created_at) "
            + "values (?, ?, ?, ?)",
            stay.firstName(), stay.lastName(), stay.email(), createdAt);

        boolean stayed = stay.status() == BookingStatus.CHECKED_IN
            || stay.status() == BookingStatus.CHECKED_OUT;
        Instant checkedInAt = stayed ? at(checkIn, CHECK_IN_AT) : null;
        Instant checkedOutAt = stay.status() == BookingStatus.CHECKED_OUT
            ? at(checkOut, CHECK_OUT_AT) : null;
        Instant cancelledAt = at(today, stay.cancelledOn(), CANCELLED_AT);
        BigDecimal rate = roomRates.get(stay.room());
        BigDecimal cancellationFee = new BigDecimal(
            stay.cancellationFee() == null ? "0.00" : stay.cancellationFee());

        long bookingId = insert("insert into booking (confirmation_code, room_id, guest_id, "
            + "check_in_date, check_out_date, adults, children, special_requests, status, "
            + "nightly_rate, cancellation_fee, created_at, checked_in_at, checked_out_at, "
            + "cancelled_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            nextConfirmationCode(), roomIds.get(stay.room()), guestId, checkIn, checkOut,
            stay.adults(), stay.children(), stay.specialRequests(), stay.status().name(), rate,
            cancellationFee, createdAt, checkedInAt, checkedOutAt, cancelledAt);
        stayIds.put(stay.key(), bookingId);

        event(bookingId, BookingEventType.CREATED, createdAt, null);
        if (stay.status() != BookingStatus.CANCELLED) {
            seedRoomNights(bookingId, roomIds.get(stay.room()), checkIn, checkOut);
        }
        if (checkedInAt != null) {
            event(bookingId, BookingEventType.CHECKED_IN, checkedInAt, null);
        }
        if (checkedOutAt != null) {
            event(bookingId, BookingEventType.CHECKED_OUT, checkedOutAt, null);
        }
        if (cancelledAt != null) {
            event(bookingId, BookingEventType.CANCELLED, cancelledAt, null);
        }

        List<FineLine> fines = seedFines(stay, bookingId, checkIn);
        seedInvoice(stay, bookingId, checkIn, checkOut, rate, cancellationFee, fines,
            checkedOutAt, cancelledAt);
    }

    private void seedRoomNights(long bookingId, long roomId, LocalDate checkIn, LocalDate checkOut) {
        for (LocalDate night : checkIn.datesUntil(checkOut).toList()) {
            insert("insert into room_night (room_id, night_date, booking_id) values (?, ?, ?)",
                roomId, night, bookingId);
        }
    }

    private List<FineLine> seedFines(Stay stay, long bookingId, LocalDate checkIn) {
        if (stay.fineReason() == null) {
            return List.of();
        }
        BigDecimal amount = new BigDecimal(stay.fineAmount());
        Instant issuedAt = at(checkIn, FINE_AT);
        insert("insert into fine (booking_id, reason, amount, issued_at, issued_by_employee_id) "
            + "values (?, ?, ?, ?, ?)",
            bookingId, stay.fineReason(), amount, issuedAt, frontDeskOf(stay.branch()));
        event(bookingId, BookingEventType.FINE_ADDED, issuedAt, stay.fineReason());
        return List.of(new FineLine(stay.fineReason(), amount));
    }

    /**
     * Only a finished stay has an invoice: a checked-out one that was paid in full, and a
     * cancelled one that is VOID when the cancellation was free and OPEN when it carries a fee.
     * Open and in-house bookings get theirs when they check out.
     */
    private void seedInvoice(Stay stay, long bookingId, LocalDate checkIn, LocalDate checkOut,
            BigDecimal rate, BigDecimal cancellationFee, List<FineLine> fines, Instant checkedOutAt,
            Instant cancelledAt) {

        boolean cancelled = stay.status() == BookingStatus.CANCELLED;
        if (stay.status() != BookingStatus.CHECKED_OUT && !cancelled) {
            return;
        }
        int nights = cancelled ? 0 : (int) ChronoUnit.DAYS.between(checkIn, checkOut);
        List<FineLine> billed = cancelled ? List.of() : fines;
        BigDecimal taxRate = branchTaxRates.get(stay.branch());
        InvoiceCalculator.Result invoice =
            InvoiceCalculator.calculate(nights, rate, taxRate, billed, cancellationFee);

        InvoiceStatus status;
        if (!cancelled) {
            status = InvoiceStatus.PAID;
        } else {
            status = invoice.total().signum() > 0 ? InvoiceStatus.OPEN : InvoiceStatus.VOID;
        }
        Instant issuedAt = cancelled ? cancelledAt : checkedOutAt;

        long invoiceId = insert("insert into invoice (booking_id, tax_rate, room_subtotal, tax, "
            + "fines, cancellation_fee, total, status, issued_at, updated_at) "
            + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            bookingId, taxRate, invoice.roomSubtotal(), invoice.tax(), invoice.fines(),
            invoice.cancellationFee(), invoice.total(), status.name(), issuedAt, issuedAt);

        int lineOrder = 0;
        for (Line line : invoice.lines()) {
            insert("insert into invoice_line (invoice_id, line_order, kind, description, quantity, "
                + "unit_amount, amount) values (?, ?, ?, ?, ?, ?, ?)",
                invoiceId, ++lineOrder, line.kind().name(), line.description(), line.quantity(),
                line.unitAmount(), line.amount());
        }

        if (status == InvoiceStatus.PAID) {
            Instant paidAt = issuedAt.plus(5, ChronoUnit.MINUTES);
            insert("insert into payment (invoice_id, amount, method, reference, paid_at, "
                + "recorded_by_employee_id) values (?, ?, ?, ?, ?, ?)",
                invoiceId, invoice.total(), stay.paymentMethod().name(),
                "DEMO-" + stay.paymentMethod().name(), paidAt, frontDeskOf(stay.branch()));
            event(bookingId, BookingEventType.PAYMENT_RECORDED, paidAt,
                stay.paymentMethod().name());
        }
    }

    // --- complaints and metadata ------------------------------------------------------------------

    private void seedComplaints(LocalDate today) {
        for (Grievance grievance : GRIEVANCES) {
            insert("insert into complaint (branch_id, booking_id, ticket_number, guest_name, "
                + "guest_email, category, description, status, resolution_note, created_at, "
                + "resolved_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                branchIds.get(grievance.branch()),
                grievance.stayKey() == null ? null : stayIds.get(grievance.stayKey()),
                nextTicketNumber(), grievance.guestName(), grievance.guestEmail(),
                grievance.category().name(), grievance.description(), grievance.status().name(),
                grievance.resolutionNote(), at(today, grievance.raisedOn(), COMPLAINED_AT),
                at(today, grievance.resolvedOn(), COMPLETED_AT));
        }
    }

    private void seedMetadata(Instant now) {
        for (String key : List.of(AppMetadata.SEEDED_AT, AppMetadata.LAST_RESET_AT)) {
            execute("insert into app_metadata (meta_key, meta_value, updated_at) values (?, ?, ?)",
                key, now.toString(), now);
        }
    }

    // --- plumbing -----------------------------------------------------------------------------------

    private void reset() {
        branchIds.clear();
        branchTaxRates.clear();
        roomTypeIds.clear();
        roomIds.clear();
        roomRates.clear();
        employeeIds.clear();
        teamIds.clear();
        stayIds.clear();
        usedCodes.clear();
        codeSource = new Random(CODE_SEED);
    }

    private void event(long bookingId, BookingEventType type, Instant occurredAt, String note) {
        insert("insert into booking_event (booking_id, event_type, occurred_at, actor, note) "
            + "values (?, ?, ?, ?, ?)", bookingId, type.name(), occurredAt, ACTOR, note);
    }

    private Long frontDeskOf(String branch) {
        return employeeIds.get(FRONT_DESK.get(branch));
    }

    private String nextConfirmationCode() {
        for (int attempt = 0; attempt < MAX_CODE_ATTEMPTS; attempt++) {
            String code = ConfirmationCodes.generate(codeSource);
            if (usedCodes.add(code)) {
                return code;
            }
        }
        throw new IllegalStateException("Could not generate a unique confirmation code");
    }

    private String nextTicketNumber() {
        for (int attempt = 0; attempt < MAX_CODE_ATTEMPTS; attempt++) {
            StringBuilder ticket = new StringBuilder("CMP-");
            for (int character = 0; character < TICKET_BODY_LENGTH; character++) {
                ticket.append(ConfirmationCodes.ALPHABET
                    .charAt(codeSource.nextInt(ConfirmationCodes.ALPHABET.length())));
            }
            if (usedCodes.add(ticket.toString())) {
                return ticket.toString();
            }
        }
        throw new IllegalStateException("Could not generate a unique ticket number");
    }

    private static String emailFor(String firstName, String lastName) {
        return (firstName + "." + lastName + EXAMPLE_COM).toLowerCase(Locale.ROOT);
    }

    private static LocalDate earlier(LocalDate one, LocalDate other) {
        return one.isBefore(other) ? one : other;
    }

    private static Instant at(LocalDate day, LocalTime time) {
        return day.atTime(time).atZone(ZONE).toInstant();
    }

    /** Null offsets mean "this never happened", so the column stays null. */
    private static Instant at(LocalDate today, Integer offsetInDays, LocalTime time) {
        return offsetInDays == null ? null : at(today.plusDays(offsetInDays), time);
    }

    private long insert(String sql, Object... values) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, new String[] {"id"});
            bind(statement, values);
            return statement;
        }, keys);
        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("No generated id came back from: " + sql);
        }
        return key.longValue();
    }

    /** For the one table without an id column, where asking for a generated key would fail. */
    private void execute(String sql, Object... values) {
        Object[] bound = new Object[values.length];
        for (int index = 0; index < values.length; index++) {
            bound[index] = jdbcValue(values[index]);
        }
        jdbc.update(sql, bound);
    }

    private static void bind(PreparedStatement statement, Object... values)
            throws SQLException {
        for (int index = 0; index < values.length; index++) {
            statement.setObject(index + 1, jdbcValue(values[index]));
        }
    }

    /** PostgreSQL has no mapping for {@code Instant}; {@code OffsetDateTime} is the JDBC 4.2 one. */
    private static Object jdbcValue(Object value) {
        return value instanceof Instant instant
            ? OffsetDateTime.ofInstant(instant, ZoneOffset.UTC) : value;
    }
}
