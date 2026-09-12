package io.github.sahajm99.innkeeper.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/** Builds the invoice lines and totals for a booking. Every amount is rounded once, HALF_UP. */
public final class InvoiceCalculator {

    private static final int MONEY_SCALE = 2;

    public record FineLine(String reason, BigDecimal amount) {
    }

    public record Line(InvoiceLineKind kind, String description, int quantity, BigDecimal unitAmount,
        BigDecimal amount) {
    }

    public record Result(List<Line> lines, BigDecimal roomSubtotal, BigDecimal tax, BigDecimal fines,
        BigDecimal cancellationFee, BigDecimal total) {
    }

    private InvoiceCalculator() {
    }

    public static Result calculate(int nightsCharged, BigDecimal nightlyRate, BigDecimal taxRate,
        List<FineLine> fines, BigDecimal cancellationFee) {

        BigDecimal rate = money(nightlyRate);
        BigDecimal roomSubtotal = money(rate.multiply(BigDecimal.valueOf(nightsCharged)));
        BigDecimal tax = money(roomSubtotal.multiply(taxRate));
        BigDecimal fee = money(cancellationFee);
        BigDecimal fineTotal = money(BigDecimal.ZERO);

        List<Line> lines = new ArrayList<>();
        if (nightsCharged > 0) {
            String nights = nightsCharged == 1 ? "1 night" : nightsCharged + " nights";
            lines.add(new Line(InvoiceLineKind.ROOM_NIGHTS, nights + " x $" + rate.toPlainString(),
                nightsCharged, rate, roomSubtotal));
        }
        if (tax.signum() > 0) {
            lines.add(new Line(InvoiceLineKind.TAX, "Occupancy tax " + percent(taxRate), 1, tax, tax));
        }
        for (FineLine fine : fines) {
            BigDecimal amount = money(fine.amount());
            fineTotal = fineTotal.add(amount);
            lines.add(new Line(InvoiceLineKind.FINE, fine.reason(), 1, amount, amount));
        }
        if (fee.signum() > 0) {
            lines.add(new Line(InvoiceLineKind.CANCELLATION_FEE, "Cancellation fee", 1, fee, fee));
        }

        BigDecimal total = roomSubtotal.add(tax).add(fineTotal).add(fee);
        return new Result(List.copyOf(lines), roomSubtotal, tax, fineTotal, fee, total);
    }

    private static BigDecimal money(BigDecimal amount) {
        return amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static String percent(BigDecimal taxRate) {
        return taxRate.movePointRight(2).stripTrailingZeros().toPlainString() + "%";
    }
}
