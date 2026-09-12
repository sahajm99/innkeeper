package io.github.sahajm99.innkeeper.seed;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

import io.github.sahajm99.innkeeper.domain.BookingStatus;
import io.github.sahajm99.innkeeper.domain.BranchDates;
import io.github.sahajm99.innkeeper.domain.InvoiceCalculator;
import io.github.sahajm99.innkeeper.domain.InvoiceCalculator.FineLine;
import io.github.sahajm99.innkeeper.domain.InvoiceCalculator.Line;
import io.github.sahajm99.innkeeper.model.InventoryItem;
import io.github.sahajm99.innkeeper.repository.BookingRepository;
import io.github.sahajm99.innkeeper.repository.InventoryItemRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Checks the seed the V2 migration applied when this context started: the row counts, the two
 * ledgers the staff dashboard is built on, and that every stored invoice is exactly what
 * {@link InvoiceCalculator} produces from the booking it belongs to.
 */
@SpringBootTest
@ActiveProfiles("test")
class SeedDataTest {

    private static final ZoneId ZONE = ZoneId.of("America/Chicago");

    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;
    @Autowired BookingRepository bookings;
    @Autowired InventoryItemRepository inventory;

    // --- what the migration inserted ----------------------------------------------------------

    @Test
    void theMigrationSeedsExactlyTheExpectedRows() {
        assertThat(countsInDatabase()).isEqualTo(SeedData.expectedCounts());
    }

    @Test
    void theChildTablesAreSeededWithTheirParents() {
        assertThat(count("fine")).isEqualTo(2);
        assertThat(count("payment")).isEqualTo(5);
        assertThat(count("invoice_line")).isEqualTo(47);
        assertThat(count("booking_event")).isEqualTo(51);
        assertThat(count("app_metadata")).isEqualTo(2);
    }

    @Test
    void appMetadataRecordsWhenTheDataWasSeededAndReset() {
        String seededAt = metadata("seeded_at");

        assertThat(seededAt).isEqualTo(metadata("last_reset_at"));
        assertThat(Instant.parse(seededAt)).isBeforeOrEqualTo(clock.instant());
    }

    // --- the two ledgers the staff dashboard is built on ---------------------------------------

    @Test
    void threeBookingsArriveToday() {
        assertThat(bookings.arrivals(null, today())).hasSize(3);
    }

    @Test
    void fourBookingsDepartTodayCountingTheOverstay() {
        assertThat(bookings.departures(null, today())).hasSize(4);
    }

    @Test
    void theDemoGuestHasTwoUpcomingBookings() {
        assertThat(bookings.findByGuestEmail("guest@example.com")).hasSize(2);
    }

    @Test
    void theStaffAndManagerAccountsAreLinkedToTheirEmployee() {
        assertThat(employeeEmailFor("staff")).isEqualTo("ben.sample@example.com");
        assertThat(employeeEmailFor("manager")).isEqualTo("ada.example@example.com");
        assertThat(jdbc.queryForObject("select count(*) from user_account "
            + "where username = 'guest' and employee_id is null", Integer.class)).isEqualTo(1);
    }

    // --- invoices -------------------------------------------------------------------------------

    @Test
    void everyStoredInvoiceIsWhatTheInvoiceCalculatorProduces() {
        List<InvoiceRow> invoices = invoiceRows();
        assertThat(invoices).hasSize(SeedData.expectedCounts().invoices());

        for (InvoiceRow invoice : invoices) {
            InvoiceCalculator.Result expected = recompute(invoice);

            assertThat(invoice.invoiceTaxRate())
                .as("invoice %d snapshots the branch tax rate", invoice.invoiceId())
                .isEqualByComparingTo(invoice.branchTaxRate());
            assertThat(invoice.roomSubtotal()).isEqualByComparingTo(expected.roomSubtotal());
            assertThat(invoice.tax()).isEqualByComparingTo(expected.tax());
            assertThat(invoice.fines()).isEqualByComparingTo(expected.fines());
            assertThat(invoice.cancellationFee()).isEqualByComparingTo(expected.cancellationFee());
            assertThat(invoice.total())
                .as("total of invoice %d", invoice.invoiceId())
                .isEqualByComparingTo(expected.total());

            assertLinesMatch(invoice, expected.lines());
        }
    }

    @Test
    void everyBookingIsChargedTheRateOfTheRoomItHolds() {
        assertThat(jdbc.queryForObject("select count(*) from booking b join room r "
            + "on r.id = b.room_id where b.nightly_rate <> r.nightly_rate", Integer.class)).isZero();
    }

    @Test
    void everyBookingCarriesAnInvoiceFromTheMomentItIsCreated() {
        assertThat(jdbc.queryForObject("select count(*) from booking b where not exists "
            + "(select 1 from invoice i where i.booking_id = b.id)", Integer.class))
            .as("bookings with no invoice")
            .isZero();
    }

    @Test
    void invoiceStatusFollowsTheBookingItBelongsTo() {
        assertThat(invoicesWithStatus("PAID")).as("the five checked-out stays").isEqualTo(5);
        assertThat(invoicesWithStatus("VOID")).as("the free cancellation").isEqualTo(1);
        assertThat(invoicesWithStatus("OPEN"))
            .as("the cancellation with a fee and the seventeen open stays")
            .isEqualTo(18);

        assertThat(jdbc.queryForObject("select count(*) from invoice i where i.status = 'PAID' "
            + "and i.total <> (select coalesce(sum(p.amount), 0) from payment p "
            + "where p.invoice_id = i.id)", Integer.class))
            .as("PAID invoices whose payments do not add up to the total")
            .isZero();
        assertThat(jdbc.queryForObject("select count(*) from payment p join invoice i "
            + "on i.id = p.invoice_id where i.status <> 'PAID'", Integer.class))
            .as("payments against an invoice that is not settled")
            .isZero();
    }

    // --- inventory ------------------------------------------------------------------------------

    @Test
    void twoItemsAreBelowTheirReorderLevelIncludingDentonToiletPaper() {
        List<InventoryItem> low = inventory.lowStock(null);

        assertThat(low).hasSize(2);
        assertThat(low).anySatisfy(item -> {
            assertThat(item.getBranch().getCode()).isEqualTo("DEN");
            assertThat(item.getName()).isEqualTo("Toilet paper");
            assertThat(item.getQuantity()).isLessThanOrEqualTo(item.getReorderLevel());
        });
    }

    // --- the data is obviously fictional ---------------------------------------------------------

    @Test
    void everySeededEmailIsOnExampleCom() {
        assertThat(emailsNotOnExampleCom("guest")).isEmpty();
        assertThat(emailsNotOnExampleCom("employee")).isEmpty();
        assertThat(emailsNotOnExampleCom("branch")).isEmpty();
        assertThat(jdbc.queryForList("select contact_email from maintenance_team "
            + "where contact_email not like ?", String.class, EXAMPLE_COM)).isEmpty();
        assertThat(jdbc.queryForList("select guest_email from complaint "
            + "where guest_email not like ?", String.class, EXAMPLE_COM)).isEmpty();
    }

    @Test
    void noGuestCarriesAPhoneNumber() {
        assertThat(count("guest")).isPositive();
        assertThat(jdbc.queryForObject("select count(*) from guest where phone is not null",
            Integer.class)).isZero();
    }

    @Test
    void everyBranchPhoneIsAnObviouslyFakeNumber() {
        assertThat(jdbc.queryForList("select phone from branch", String.class))
            .allSatisfy(phone -> assertThat(phone).startsWith("(000) 000-"));
    }

    // --- the nightly reset runs the same class ----------------------------------------------------

    /**
     * Every context in this JVM shares one database, so the finally block puts a single clean seed
     * back however far through the body a failure happened.
     */
    @Test
    void deletingEverythingAndSeedingAgainRestoresTheSameCounts() {
        SeedData seedData = new SeedData(jdbc, clock);
        try {
            seedData.deleteAll();
            assertThat(count("booking")).isZero();
            assertThat(count("branch")).isZero();
            assertThat(count("app_metadata")).isZero();

            seedData.seed();

            assertThat(countsInDatabase()).isEqualTo(SeedData.expectedCounts());
            assertThat(bookings.arrivals(null, today())).hasSize(3);
            assertThat(bookings.departures(null, today())).hasSize(4);
        } finally {
            seedData.deleteAll();
            seedData.seed();
        }
    }

    // --- plumbing ----------------------------------------------------------------------------------

    private static final String EXAMPLE_COM = "%@example.com";

    private record InvoiceRow(long invoiceId, long bookingId, String bookingStatus, LocalDate checkIn,
        LocalDate checkOut, BigDecimal nightlyRate, BigDecimal bookingFee, BigDecimal branchTaxRate,
        BigDecimal invoiceTaxRate, BigDecimal roomSubtotal, BigDecimal tax, BigDecimal fines,
        BigDecimal cancellationFee, BigDecimal total) {
    }

    private record LineRow(int lineOrder, String kind, String description, int quantity,
        BigDecimal unitAmount, BigDecimal amount) {
    }

    private LocalDate today() {
        return BranchDates.today(clock, ZONE);
    }

    private int count(String table) {
        Integer rows = jdbc.queryForObject("select count(*) from " + table, Integer.class);
        return rows == null ? 0 : rows;
    }

    private String metadata(String key) {
        return jdbc.queryForObject("select meta_value from app_metadata where meta_key = ?",
            String.class, key);
    }

    private String employeeEmailFor(String username) {
        return jdbc.queryForObject("select e.email from user_account u "
            + "join employee e on e.id = u.employee_id where u.username = ?", String.class, username);
    }

    private int invoicesWithStatus(String status) {
        Integer rows = jdbc.queryForObject("select count(*) from invoice where status = ?",
            Integer.class, status);
        return rows == null ? 0 : rows;
    }

    private List<String> emailsNotOnExampleCom(String table) {
        return jdbc.queryForList("select email from " + table + " where email not like ?",
            String.class, EXAMPLE_COM);
    }

    private SeedData.Counts countsInDatabase() {
        return new SeedData.Counts(count("branch"), count("room_type"), count("room"), count("guest"),
            count("booking"), count("room_night"), count("invoice"), count("employee"),
            count("user_account"), count("maintenance_team"), count("maintenance_request"),
            count("inventory_item"), count("parking_space"), count("complaint"));
    }

    private List<InvoiceRow> invoiceRows() {
        return jdbc.query("select i.id as invoice_id, b.id as booking_id, b.status as booking_status, "
            + "b.check_in_date, b.check_out_date, b.nightly_rate, b.cancellation_fee as booking_fee, "
            + "br.tax_rate as branch_tax_rate, i.tax_rate as invoice_tax_rate, i.room_subtotal, "
            + "i.tax, i.fines, i.cancellation_fee, i.total "
            + "from invoice i join booking b on b.id = i.booking_id "
            + "join room r on r.id = b.room_id join branch br on br.id = r.branch_id order by i.id",
            (rs, row) -> new InvoiceRow(
                rs.getLong("invoice_id"),
                rs.getLong("booking_id"),
                rs.getString("booking_status"),
                rs.getObject("check_in_date", LocalDate.class),
                rs.getObject("check_out_date", LocalDate.class),
                rs.getBigDecimal("nightly_rate"),
                rs.getBigDecimal("booking_fee"),
                rs.getBigDecimal("branch_tax_rate"),
                rs.getBigDecimal("invoice_tax_rate"),
                rs.getBigDecimal("room_subtotal"),
                rs.getBigDecimal("tax"),
                rs.getBigDecimal("fines"),
                rs.getBigDecimal("cancellation_fee"),
                rs.getBigDecimal("total")));
    }

    private InvoiceCalculator.Result recompute(InvoiceRow invoice) {
        boolean cancelled = BookingStatus.valueOf(invoice.bookingStatus()) == BookingStatus.CANCELLED;
        int nights = cancelled ? 0
            : (int) ChronoUnit.DAYS.between(invoice.checkIn(), invoice.checkOut());
        List<FineLine> fines = jdbc.query(
            "select reason, amount from fine where booking_id = ? order by id",
            (rs, row) -> new FineLine(rs.getString("reason"), rs.getBigDecimal("amount")),
            invoice.bookingId());
        return InvoiceCalculator.calculate(nights, invoice.nightlyRate(), invoice.invoiceTaxRate(),
            fines, invoice.bookingFee());
    }

    private void assertLinesMatch(InvoiceRow invoice, List<Line> expected) {
        List<LineRow> stored = jdbc.query("select line_order, kind, description, quantity, "
            + "unit_amount, amount from invoice_line where invoice_id = ? order by line_order",
            (rs, row) -> new LineRow(rs.getInt("line_order"), rs.getString("kind"),
                rs.getString("description"), rs.getInt("quantity"), rs.getBigDecimal("unit_amount"),
                rs.getBigDecimal("amount")),
            invoice.invoiceId());

        assertThat(stored).as("lines of invoice %d", invoice.invoiceId()).hasSameSizeAs(expected);
        for (int index = 0; index < stored.size(); index++) {
            LineRow line = stored.get(index);
            Line want = expected.get(index);
            assertThat(line.lineOrder()).isEqualTo(index + 1);
            assertThat(line.kind()).isEqualTo(want.kind().name());
            assertThat(line.description()).isEqualTo(want.description());
            assertThat(line.quantity()).isEqualTo(want.quantity());
            assertThat(line.unitAmount()).isEqualByComparingTo(want.unitAmount());
            assertThat(line.amount()).isEqualByComparingTo(want.amount());
        }
    }
}
