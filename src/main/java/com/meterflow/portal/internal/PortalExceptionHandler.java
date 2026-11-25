package com.meterflow.portal.internal;

import com.meterflow.billing.api.BillingResourceNotFoundException;
import com.meterflow.catalog.api.CatalogResourceNotFoundException;
import com.meterflow.identity.api.IdentityResourceNotFoundException;
import com.meterflow.identity.api.UnauthorizedCustomerAccessException;
import com.meterflow.metering.api.MeteringResourceNotFoundException;
import com.meterflow.notifications.api.NotificationNotFoundException;
import com.meterflow.payments.api.PaymentResourceNotFoundException;
import com.meterflow.subscriptions.api.SubscriptionNotFoundException;
import com.meterflow.webhooks.api.WebhookResourceNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.servlet.ModelAndView;

/**
 * Clean HTTP status mapping for server-rendered Thymeleaf portal controllers so tenant-isolation
 * denials return HTTP 403 Forbidden and missing entities return HTTP 404 Not Found.
 */
@ControllerAdvice(annotations = Controller.class)
class PortalExceptionHandler {

    @ExceptionHandler(UnauthorizedCustomerAccessException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    ModelAndView handleUnauthorizedCustomerAccess(UnauthorizedCustomerAccessException ex) {
        ModelAndView mav = new ModelAndView("index");
        mav.addObject("errorMessage", "Access is denied.");
        return mav;
    }

    @ExceptionHandler({
            IdentityResourceNotFoundException.class,
            CatalogResourceNotFoundException.class,
            SubscriptionNotFoundException.class,
            MeteringResourceNotFoundException.class,
            BillingResourceNotFoundException.class,
            PaymentResourceNotFoundException.class,
            WebhookResourceNotFoundException.class,
            NotificationNotFoundException.class
    })
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ModelAndView handleNotFound(RuntimeException ex) {
        ModelAndView mav = new ModelAndView("index");
        mav.addObject("errorMessage", ex.getMessage());
        return mav;
    }
}
