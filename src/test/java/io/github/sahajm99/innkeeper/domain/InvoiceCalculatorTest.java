package io.github.sahajm99.innkeeper.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import io.github.sahajm99.innkeeper.domain.InvoiceCalculator.FineLine;
import io.github.sahajm99.innkeeper.domain.InvoiceCalculator.Line;
import io.github.sahajm99.innkeeper.domain.InvoiceCalculator.Result;

class InvoiceCalculatorTest {

    private static final BigDecimal RATE = new BigDecimal("89.00");
    private static final BigDecimal TAX_RATE = new BigDecimal("0.1300");
    private static final BigDecimal NO_FEE = BigDecimal.ZERO;

    @Test
    void chargesTheRoomNightsAndTheTaxOnThem() {
        Result result = InvoiceCalculator.calculate(3, RATE, TAX_RATE, List.of(), NO_FEE);

        assertThat(result.roomSubtotal()).isEqualTo(new BigDecimal("267.00"));
        assertThat(result.tax()).isEqualTo(new BigDecimal("34.71"));
        assertThat(result.total()).isEqualTo(new BigDecimal("301.71"));
        assertThat(result.lines()).hasSize(2);

        Line roomNights = result.lines().get(0);
        assertThat(roomNights.kind()).isEqualTo(InvoiceLineKind.ROOM_NIGHTS);
        assertThat(roomNights.description()).isEqualTo("3 nights x $89.00");
        assertThat(roomNights.quantity()).isEqualTo(3);
        assertThat(roomNights.unitAmount()).isEqualTo(new BigDecimal("89.00"));
        assertThat(roomNights.amount()).isEqualTo(new BigDecimal("267.00"));

        Line tax = result.lines().get(1);
        assertThat(tax.kind()).isEqualTo(InvoiceLineKind.TAX);
        assertThat(tax.description()).isEqualTo("Occupancy tax 13%");
        assertThat(tax.amount()).isEqualTo(new BigDecimal("34.71"));
    }

    @Test
    void roundsTheTaxOnceHalfUp() {
        Result result = InvoiceCalculator.calculate(3, RATE, new BigDecimal("0.0825"), List.of(), NO_FEE);

        assertThat(result.tax()).isEqualTo(new BigDecimal("22.03"));
        assertThat(result.total()).isEqualTo(new BigDecimal("289.03"));
        assertThat(result.lines().get(1).description()).isEqualTo("Occupancy tax 8.25%");
    }

    @Test
    void addsALineForEachFineAndDoesNotTaxIt() {
        Result result = InvoiceCalculator.calculate(3, RATE, TAX_RATE,
            List.of(new FineLine("Smoking in room", new BigDecimal("40.00"))), NO_FEE);

        assertThat(result.fines()).isEqualTo(new BigDecimal("40.00"));
        assertThat(result.tax()).isEqualTo(new BigDecimal("34.71"));
        assertThat(result.total()).isEqualTo(new BigDecimal("341.71"));
        assertThat(result.lines()).hasSize(3);

        Line fine = result.lines().get(2);
        assertThat(fine.kind()).isEqualTo(InvoiceLineKind.FINE);
        assertThat(fine.description()).isEqualTo("Smoking in room");
        assertThat(fine.amount()).isEqualTo(new BigDecimal("40.00"));
    }

    @Test
    void billsACancelledStayOnlyTheCancellationFee() {
        Result result = InvoiceCalculator.calculate(0, RATE, TAX_RATE, List.of(), new BigDecimal("89.00"));

        assertThat(result.roomSubtotal()).isEqualTo(new BigDecimal("0.00"));
        assertThat(result.tax()).isEqualTo(new BigDecimal("0.00"));
        assertThat(result.cancellationFee()).isEqualTo(new BigDecimal("89.00"));
        assertThat(result.total()).isEqualTo(new BigDecimal("89.00"));
        assertThat(result.lines()).hasSize(1);

        Line fee = result.lines().get(0);
        assertThat(fee.kind()).isEqualTo(InvoiceLineKind.CANCELLATION_FEE);
        assertThat(fee.description()).isEqualTo("Cancellation fee");
        assertThat(fee.amount()).isEqualTo(new BigDecimal("89.00"));
    }

    @Test
    void describesASingleNightInTheSingular() {
        Result result = InvoiceCalculator.calculate(1, RATE, BigDecimal.ZERO, List.of(), NO_FEE);

        assertThat(result.lines()).hasSize(1);
        assertThat(result.lines().get(0).description()).isEqualTo("1 night x $89.00");
    }

    @Test
    void totalAlwaysEqualsSubtotalPlusTaxPlusFinesPlusCancellationFee() {
        Random random = new Random(20260912L);

        for (int run = 0; run < 50; run++) {
            int nights = random.nextInt(0, 31);
            BigDecimal nightlyRate = BigDecimal.valueOf(random.nextInt(4000, 60000), 2);
            BigDecimal taxRate = BigDecimal.valueOf(random.nextInt(0, 2000), 4);
            List<FineLine> fines = random.nextBoolean()
                ? List.of(new FineLine("Damage", BigDecimal.valueOf(random.nextInt(1, 20000), 2)))
                : List.of();
            BigDecimal cancellationFee = random.nextBoolean() ? nightlyRate : BigDecimal.ZERO;

            Result result = InvoiceCalculator.calculate(nights, nightlyRate, taxRate, fines, cancellationFee);

            assertThat(result.total()).isEqualTo(
                result.roomSubtotal().add(result.tax()).add(result.fines()).add(result.cancellationFee()));
            assertThat(result.total().scale()).isEqualTo(2);
        }
    }
}
