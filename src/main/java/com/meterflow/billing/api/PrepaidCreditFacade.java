package com.meterflow.billing.api;

import com.meterflow.shared.SupportedCurrency;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface PrepaidCreditFacade {

    CreditGrantSummary issueCreditGrant(IssueCreditGrantCommand command);

    CustomerCreditBalance getCustomerCreditBalance(UUID customerId, SupportedCurrency currency);

    Page<CreditGrantSummary> listCustomerCreditGrants(UUID customerId, Pageable pageable);

    Page<CreditTransactionRecord> listCustomerCreditTransactions(UUID customerId, Pageable pageable);
}
