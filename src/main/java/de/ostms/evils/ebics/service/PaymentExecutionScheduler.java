package de.ostms.evils.ebics.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PaymentExecutionScheduler {
    private final PaymentOrderService payments;
    public PaymentExecutionScheduler(PaymentOrderService payments) { this.payments = payments; }
    @Scheduled(fixedDelay = 30000)
    public void bookDuePayments() { payments.bookDuePayments(); }
}
