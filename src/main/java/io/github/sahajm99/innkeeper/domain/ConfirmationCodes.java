package io.github.sahajm99.innkeeper.domain;

import java.util.Random;
import java.util.regex.Pattern;

/** Booking confirmation codes, drawn from an alphabet without characters that can be misread. */
public final class ConfirmationCodes {

    public static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    public static final Pattern PATTERN = Pattern.compile("INN-[A-Z2-9]{6}");

    private static final String PREFIX = "INN-";
    private static final int BODY_LENGTH = 6;

    private ConfirmationCodes() {
    }

    public static String generate(Random random) {
        StringBuilder code = new StringBuilder(PREFIX);
        for (int i = 0; i < BODY_LENGTH; i++) {
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }

    public static boolean isValid(String code) {
        return code != null && PATTERN.matcher(code).matches();
    }
}
