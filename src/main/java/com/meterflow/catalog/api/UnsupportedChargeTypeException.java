package com.meterflow.catalog.api;

public final class UnsupportedChargeTypeException extends CatalogException {

    public UnsupportedChargeTypeException(String chargeType) {
        super("Unsupported charge type: '" + chargeType + "'");
    }
}
