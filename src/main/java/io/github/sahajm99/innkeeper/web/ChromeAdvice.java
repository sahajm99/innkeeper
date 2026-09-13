package io.github.sahajm99.innkeeper.web;

import io.github.sahajm99.innkeeper.service.AboutService;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * The two things every page needs and no page should have to ask for: how to write a date, and
 * what the footer says about this deployment.
 *
 * <p>The footer line is the honest part of the demo - which commit, which database, when the data
 * was last thrown away - so it belongs on every page rather than only on {@code /about}. Putting
 * it here means no controller carries a model attribute it does not use.</p>
 */
@ControllerAdvice(basePackages = "io.github.sahajm99.innkeeper.web")
public class ChromeAdvice {

    private final AboutService about;
    private final Dates dates;

    public ChromeAdvice(AboutService about, Dates dates) {
        this.about = about;
        this.dates = dates;
    }

    @ModelAttribute("dates")
    public Dates dates() {
        return dates;
    }

    @ModelAttribute("underTheHood")
    public AboutService.Info underTheHood() {
        return about.info();
    }

    /** The path being rendered, so the header can mark the link the visitor is already on. */
    @ModelAttribute("path")
    public String path(HttpServletRequest request) {
        return request.getRequestURI();
    }
}
