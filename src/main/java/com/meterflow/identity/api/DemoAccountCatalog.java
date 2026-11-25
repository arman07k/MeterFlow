package com.meterflow.identity.api;

import java.util.List;

/**
 * Public read-only contract exposing opt-in academic demonstration accounts to the web portal.
 */
public interface DemoAccountCatalog {

    boolean isDemoModeEnabled();

    List<DemoAccountCard> getDemoAccounts();

    String getDemoCustomerExternalKey();
}
