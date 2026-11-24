package com.meterflow.portal.internal;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Minimal bootstrap portal shell routes only.
 * Domain admin, operator, and customer features are implemented in dedicated tasks.
 */
@Controller
public class FoundationPortalController {

    @GetMapping("/")
    public String home() {
        return "index";
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }
}
