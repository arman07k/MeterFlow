package com.meterflow.catalog.api;

public abstract class CatalogException extends RuntimeException {

    protected CatalogException(String message) {
        super(message);
    }

    protected CatalogException(String message, Throwable cause) {
        super(message, cause);
    }
}
