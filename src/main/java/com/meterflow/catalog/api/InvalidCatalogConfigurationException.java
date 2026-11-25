package com.meterflow.catalog.api;

public final class InvalidCatalogConfigurationException extends CatalogException {

    public InvalidCatalogConfigurationException(String message) {
        super(message);
    }

    public InvalidCatalogConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
