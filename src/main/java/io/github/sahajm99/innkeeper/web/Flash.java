package io.github.sahajm99.innkeeper.web;

import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The one line a page shows after a redirect.
 *
 * <p>Every mutation is POST then redirect, so the sentence that says what happened cannot be a
 * model attribute: it has to survive one redirect and no more, which is what a flash attribute is.
 * Two names only - message and error - so the layout can render them without knowing which page
 * put them there.</p>
 */
public final class Flash {

    public static final String MESSAGE = "message";
    public static final String ERROR = "error";

    private Flash() {
    }

    /** Something worked: "Booking INN-ABC123 cancelled." */
    public static void message(RedirectAttributes attributes, String message) {
        attributes.addFlashAttribute(MESSAGE, message);
    }

    /** Something did not: "That room was just taken for those dates." */
    public static void error(RedirectAttributes attributes, String message) {
        attributes.addFlashAttribute(ERROR, message);
    }
}
