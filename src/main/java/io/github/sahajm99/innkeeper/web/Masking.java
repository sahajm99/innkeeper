package io.github.sahajm99.innkeeper.web;

/**
 * How a guest's contact details look to somebody behind the desk.
 *
 * <p>A clerk needs to recognise an address, not to read it: the first letter and the domain are
 * enough to match what a guest reads out over the counter, and they are all a screen in a lobby
 * should ever show. Phone numbers get nothing at all, because there is no half of a phone number
 * that is both useful and safe.</p>
 *
 * <p>The methods are instance methods as well as static ones so a template can hold this as the
 * model attribute {@code mask} and write {@code ${mask.email(...)}} instead of naming the class.
 * The rule then lives in one place, which is the only way a page can be trusted not to leak.</p>
 */
public final class Masking {

    /** What is printed instead of anything that cannot be shown. */
    public static final String HIDDEN = "***";

    private Masking() {
    }

    /** The shared instance the staff pages read; it has no state of its own. */
    public static Masking forPages() {
        return Holder.INSTANCE;
    }

    /** "grace.example@example.com" becomes "g***@example.com". */
    public static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return "";
        }
        String trimmed = email.trim();
        int at = trimmed.indexOf('@');
        if (at < 1 || at == trimmed.length() - 1) {
            return HIDDEN;
        }
        return trimmed.charAt(0) + HIDDEN + trimmed.substring(at);
    }

    /** A phone number is never shown; a missing one is an empty string rather than a mask. */
    public static String maskPhone(String phone) {
        return phone == null || phone.isBlank() ? "" : HIDDEN;
    }

    public String email(String email) {
        return maskEmail(email);
    }

    public String phone(String phone) {
        return maskPhone(phone);
    }

    private static final class Holder {
        private static final Masking INSTANCE = new Masking();
    }
}
