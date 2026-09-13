package io.github.sahajm99.innkeeper.web;

import io.github.sahajm99.innkeeper.service.AboutService;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** The page that answers "what am I looking at": the build, the database and the demo data. */
@Controller
public class AboutController {

    private final AboutService about;

    public AboutController(AboutService about) {
        this.about = about;
    }

    @GetMapping("/about")
    public String about(Model model) {
        model.addAttribute("info", about.info());
        return "about";
    }
}
