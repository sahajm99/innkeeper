package io.github.sahajm99.innkeeper.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sahajm99.innkeeper.domain.BranchDates;
import io.github.sahajm99.innkeeper.model.Room;
import io.github.sahajm99.innkeeper.repository.RoomRepository;
import io.github.sahajm99.innkeeper.service.ResetService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Booking, finding and cancelling through the API.
 *
 * <p>Every test books far enough ahead that it cannot collide with a seeded stay, and each one puts
 * the demo data back afterwards: this database is shared with the tests that assert on the seed, so
 * a test that leaves a booking behind would fail them in some orderings and not others.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BookingApiTest {

    private static final ZoneId ZONE = ZoneId.of("America/Chicago");
    private static final String EMAIL = "api.guest@example.com";
    private static final String CODE_PATTERN = "INN-[A-Z2-9]{6}";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired RoomRepository rooms;
    @Autowired ResetService reset;
    @Autowired Clock clock;

    private Long roomId;
    private LocalDate checkIn;
    private LocalDate checkOut;

    @BeforeEach
    void findAFarOffRoom() {
        Room room = rooms.findByBranchCodeAndRoomNumber("DEN", "101").orElseThrow();
        roomId = room.getId();
        checkIn = BranchDates.today(clock, ZONE).plusDays(250);
        checkOut = checkIn.plusDays(2);
    }

    @AfterEach
    void putTheDemoDataBack() {
        reset.reset("test");
    }

    // --- creating -----------------------------------------------------------------------------------

    @Test
    void bookingARoomAnswersWithTheStayAndWhereToReadIt() throws Exception {
        JsonNode booking = objectMapper.readTree(mockMvc.perform(create(body()))
            .andExpect(status().isCreated())
            .andExpect(header().string("Location", org.hamcrest.Matchers.matchesPattern(
                "/bookings/" + CODE_PATTERN)))
            .andExpect(jsonPath("$.status").value("CONFIRMED"))
            .andExpect(jsonPath("$.nights").value(2))
            .andExpect(jsonPath("$.room.roomNumber").value("101"))
            .andExpect(jsonPath("$.guest.email").value(EMAIL))
            .andReturn().getResponse().getContentAsString());

        assertThat(booking.get("code").asText()).matches(CODE_PATTERN);
    }

    @Test
    void theInvoiceIsTwoNightsAtTheRoomRatePlusTax() throws Exception {
        JsonNode booking = objectMapper.readTree(mockMvc.perform(create(body()))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString());

        JsonNode invoice = booking.get("invoice");
        BigDecimal rate = booking.get("room").get("nightlyRate").decimalValue();
        BigDecimal taxRate = invoice.get("taxRate").decimalValue();
        BigDecimal subtotal = money(rate.multiply(BigDecimal.valueOf(2)));
        BigDecimal tax = money(subtotal.multiply(taxRate));

        assertThat(taxRate).isEqualByComparingTo(new BigDecimal("0.1300"));
        assertThat(invoice.get("roomSubtotal").decimalValue()).isEqualByComparingTo(subtotal);
        assertThat(invoice.get("tax").decimalValue()).isEqualByComparingTo(tax);
        assertThat(invoice.get("total").decimalValue()).isEqualByComparingTo(subtotal.add(tax));
        assertThat(invoice.get("balance").decimalValue()).isEqualByComparingTo(subtotal.add(tax));
        assertThat(invoice.get("status").asText()).isEqualTo("OPEN");
    }

    @Test
    void aCheckOutBeforeTheCheckInIsARefusalAgainstThatField() throws Exception {
        Map<String, Object> backwards = body();
        backwards.put("checkOut", checkIn.minusDays(1).toString());

        mockMvc.perform(create(backwards))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.errors.checkOut").isNotEmpty());
    }

    @Test
    void anEmailThatIsNotOneIsARefusalAgainstThatField() throws Exception {
        Map<String, Object> wrong = body();
        wrong.put("email", "not-an-email");

        mockMvc.perform(create(wrong))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors.email").isNotEmpty());
    }

    @Test
    void twoBookingsForTheSameRoomAndNightsLeaveTheSecondWithAConflict() throws Exception {
        mockMvc.perform(create(body())).andExpect(status().isCreated());

        mockMvc.perform(create(body()))
            .andExpect(status().isConflict())
            .andExpect(header().string("Content-Type",
                org.hamcrest.Matchers.startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE)))
            .andExpect(jsonPath("$.title").value("Room unavailable"))
            .andExpect(jsonPath("$.status").value(409));
    }

    // --- finding ------------------------------------------------------------------------------------

    @Test
    void aCodeAndItsEmailReadTheBookingBack() throws Exception {
        String code = createdCode();

        JsonNode booking = objectMapper.readTree(mockMvc.perform(lookup(code, EMAIL))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(code))
            .andExpect(jsonPath("$.cancellationDeadline").isNotEmpty())
            .andReturn().getResponse().getContentAsString());

        assertThat(booking.get("cancellationFeeNow").decimalValue())
            .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void aCodeWithSomebodyElseEmailIsANotFound() throws Exception {
        String code = createdCode();

        mockMvc.perform(lookup(code, "someone.else@example.com"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.title").value("Not found"));
    }

    @Test
    void aCodeThatIsNotShapedLikeOneIsARefusal() throws Exception {
        mockMvc.perform(lookup("nonsense", EMAIL))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors.code").isNotEmpty());
    }

    // --- cancelling ---------------------------------------------------------------------------------

    @Test
    void cancellingWellBeforeTheStayCostsNothing() throws Exception {
        String code = createdCode();

        JsonNode cancelled = objectMapper.readTree(mockMvc.perform(cancel(code, EMAIL))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(code))
            .andExpect(jsonPath("$.status").value("CANCELLED"))
            .andReturn().getResponse().getContentAsString());

        assertThat(cancelled.get("cancellationFee").decimalValue())
            .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void cancellingTwiceIsAConflict() throws Exception {
        String code = createdCode();
        mockMvc.perform(cancel(code, EMAIL)).andExpect(status().isOk());

        mockMvc.perform(cancel(code, EMAIL))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void cancellingWithTheWrongEmailIsANotFound() throws Exception {
        String code = createdCode();

        mockMvc.perform(cancel(code, "someone.else@example.com"))
            .andExpect(status().isNotFound());
    }

    // --- plumbing -----------------------------------------------------------------------------------

    private String createdCode() throws Exception {
        JsonNode booking = objectMapper.readTree(mockMvc.perform(create(body()))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString());
        return booking.get("code").asText();
    }

    private Map<String, Object> body() {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("roomId", roomId);
        body.put("checkIn", checkIn.toString());
        body.put("checkOut", checkOut.toString());
        body.put("adults", 2);
        body.put("children", 0);
        body.put("firstName", "Api");
        body.put("lastName", "Guest");
        body.put("email", EMAIL);
        body.put("phone", null);
        body.put("specialRequests", null);
        return body;
    }

    private MockHttpServletRequestBuilder create(Map<String, Object> body) throws Exception {
        return post("/api/bookings")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(body));
    }

    private MockHttpServletRequestBuilder lookup(String code, String email) throws Exception {
        return post("/api/bookings/lookup")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("code", code, "email", email)));
    }

    private MockHttpServletRequestBuilder cancel(String code, String email) throws Exception {
        return post("/api/bookings/{code}/cancel", code)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("email", email)));
    }

    private BigDecimal money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }
}
