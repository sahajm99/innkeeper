package io.github.sahajm99.innkeeper.api.dto;

import java.math.BigDecimal;

/** What cancelling cost: nothing inside the free window, one night after it. */
public record CancelResponse(String code, String status, BigDecimal cancellationFee) {
}
