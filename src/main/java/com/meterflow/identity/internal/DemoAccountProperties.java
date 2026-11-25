package com.meterflow.identity.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalized configuration for opt-in academic demonstration accounts.
 * Disabled by default; passwords must always be provided via runtime environment variables.
 */
@ConfigurationProperties(prefix = "meterflow.demo")
class DemoAccountProperties {

    private boolean enabled = false;
    private boolean seedSampleData = true;
    private String customerExternalKey = "demo-acme-cloud";
    private String customerName = "Acme Cloud Demo";
    private AccountConfig admin = new AccountConfig("demo-admin@meterflow.demo", "Demo Administrator", "");
    private AccountConfig operator = new AccountConfig("demo-operator@meterflow.demo", "Demo Operator", "");
    private AccountConfig customer = new AccountConfig("demo-customer@meterflow.demo", "Demo Customer", "");

    boolean isEnabled() {
        return enabled;
    }

    void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    boolean isSeedSampleData() {
        return seedSampleData;
    }

    void setSeedSampleData(boolean seedSampleData) {
        this.seedSampleData = seedSampleData;
    }

    String getCustomerExternalKey() {
        return customerExternalKey;
    }

    void setCustomerExternalKey(String customerExternalKey) {
        this.customerExternalKey = customerExternalKey;
    }

    String getCustomerName() {
        return customerName;
    }

    void setCustomerName(String customerName) {
        this.customerName = customerName;
    }

    AccountConfig getAdmin() {
        return admin;
    }

    void setAdmin(AccountConfig admin) {
        this.admin = admin;
    }

    AccountConfig getOperator() {
        return operator;
    }

    void setOperator(AccountConfig operator) {
        this.operator = operator;
    }

    AccountConfig getCustomer() {
        return customer;
    }

    void setCustomer(AccountConfig customer) {
        this.customer = customer;
    }

    static class AccountConfig {

        private String email;
        private String fullName;
        private String password;

        AccountConfig() {
        }

        AccountConfig(String email, String fullName, String password) {
            this.email = email;
            this.fullName = fullName;
            this.password = password;
        }

        String getEmail() {
            return email;
        }

        void setEmail(String email) {
            this.email = email;
        }

        String getFullName() {
            return fullName;
        }

        void setFullName(String fullName) {
            this.fullName = fullName;
        }

        String getPassword() {
            return password;
        }

        void setPassword(String password) {
            this.password = password;
        }

        @Override
        public String toString() {
            return "AccountConfig[email=" + email + ", fullName=" + fullName + ", password=***]";
        }
    }
}
