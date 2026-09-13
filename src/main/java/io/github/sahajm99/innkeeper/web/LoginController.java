package io.github.sahajm99.innkeeper.web;

import io.github.sahajm99.innkeeper.seed.DemoAccounts;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The sign-in page. Spring Security processes the POST, so this only has to render the form - and
 * the three demo logins, which are printed because a portfolio demo nobody can sign into is a
 * screenshot.
 */
@Controller
public class LoginController {

    @GetMapping("/login")
    public String login(Model model) {
        model.addAttribute("accounts", DemoAccounts.ALL);
        return "login";
    }
}
