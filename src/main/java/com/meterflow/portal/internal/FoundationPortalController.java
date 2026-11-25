package com.meterflow.portal.internal;

import com.meterflow.identity.api.DemoAccountCatalog;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Public landing and sign-in portal routes.
 */
@Controller
public class FoundationPortalController {

    private final ObjectProvider<DemoAccountCatalog> demoAccountCatalogProvider;

    public FoundationPortalController(ObjectProvider<DemoAccountCatalog> demoAccountCatalogProvider) {
        this.demoAccountCatalogProvider = demoAccountCatalogProvider;
    }

    @GetMapping("/")
    public String home(Model model) {
        DemoAccountCatalog catalog = demoAccountCatalogProvider.getIfAvailable();
        model.addAttribute("demoModeEnabled", catalog != null && catalog.isDemoModeEnabled());
        return "index";
    }

    @GetMapping("/login")
    public String login(Model model) {
        DemoAccountCatalog catalog = demoAccountCatalogProvider.getIfAvailable();
        boolean demoEnabled = catalog != null && catalog.isDemoModeEnabled();
        model.addAttribute("demoModeEnabled", demoEnabled);
        model.addAttribute("demoAccounts", demoEnabled ? catalog.getDemoAccounts() : List.of());
        return "login";
    }
}
