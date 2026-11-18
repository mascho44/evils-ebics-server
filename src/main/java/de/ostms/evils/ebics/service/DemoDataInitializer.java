package de.ostms.evils.ebics.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
class DemoDataInitializer implements ApplicationRunner {
    private final BankService bankService;
    private final TransactionService transactionService;
    private final PaymentAuthorizationService paymentAuthorizations;
    private final AdminUserService adminUsers;
    DemoDataInitializer(BankService bankService, TransactionService transactionService, PaymentAuthorizationService paymentAuthorizations, AdminUserService adminUsers) { this.bankService=bankService; this.transactionService=transactionService; this.paymentAuthorizations=paymentAuthorizations; this.adminUsers=adminUsers; }
    public void run(ApplicationArguments args) { bankService.seedDemoData(); bankService.backfillLegacyHostId(); transactionService.seedDemoData(); paymentAuthorizations.seedDemoAuthorization(); adminUsers.seedBootstrapUser(); }
}
