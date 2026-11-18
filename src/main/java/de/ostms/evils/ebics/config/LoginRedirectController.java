package de.ostms.evils.ebics.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Keeps links/bookmarks made by earlier GUI builds on the canonical login page. */
@Controller
public class LoginRedirectController {
    @GetMapping("/admin/login")
    public String legacyAdminLogin() {
        return "redirect:/login";
    }
}
