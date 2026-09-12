package io.github.sahajm99.innkeeper.domain;

/** Guests must fit the room type and include an adult. */
public final class OccupancyRule {

    private OccupancyRule() {
    }

    public static void check(int adults, int children, int maxOccupancy) {
        if (adults < 1) {
            throw new BookingRuleException("adults", "At least one adult is required");
        }
        if (adults + children > maxOccupancy) {
            String limit = maxOccupancy == 1 ? "1 guest" : maxOccupancy + " guests";
            throw new BookingRuleException(children == 0 ? "adults" : "children",
                "This room sleeps up to " + limit);
        }
    }
}
