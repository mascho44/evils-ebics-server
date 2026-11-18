package de.ostms.evils.ebics.service;

import de.ostms.evils.ebics.domain.*;
import de.ostms.evils.ebics.persistence.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * Payment orders created in the console or received by EBICS upload: listing, detail with signatures
 * and event history, signing and booking. A payment is approved by one signature of class E or by A and B
 * together; class T (technical) never approves. A future-dated order stays {@code SCHEDULED}, and
 * {@link #bookDuePayments()} books it once it falls due, each order in its own transaction.
 */
@Service
public class PaymentOrderService {
    @org.springframework.beans.factory.annotation.Autowired private PaymentHistoryService history;
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;
    private final PaymentOrderRepository orders; private final PaymentSignatureRepository signatures;
    private final AccountRepository accounts; private final ParticipantRepository participants;
    private final PaymentAuthorizationService authorizations; private final TransactionService transactions;
    private final PaymentSubmissionRepository submissions;
    public PaymentOrderService(PaymentOrderRepository orders, PaymentSignatureRepository signatures, AccountRepository accounts, ParticipantRepository participants, PaymentAuthorizationService authorizations, TransactionService transactions, PaymentSubmissionRepository submissions) { this.orders=orders; this.signatures=signatures; this.accounts=accounts; this.participants=participants; this.authorizations=authorizations; this.transactions=transactions;this.submissions=submissions; }
    public record Detail(PaymentOrder order, List<PaymentSignature> signatures, String source,List<PaymentEvent> events) { }
    @Transactional public PaymentOrder create(String iban, BigDecimal amount, String beneficiaryName, String beneficiaryIban, String beneficiaryBic, String purpose, String endToEndId, LocalDate executionDate) {
        return createInternal(iban, amount, beneficiaryName, beneficiaryIban, beneficiaryBic, purpose, endToEndId, executionDate, null);
    }
    @Transactional public PaymentOrder createFromEbics(String iban, BigDecimal amount, String beneficiaryName, String beneficiaryIban, String beneficiaryBic, String purpose, String endToEndId, LocalDate executionDate, String originalMessageId) {
        return createInternal(iban, amount, beneficiaryName, beneficiaryIban, beneficiaryBic, purpose, endToEndId, executionDate, originalMessageId);
    }
    private PaymentOrder createInternal(String iban, BigDecimal amount, String beneficiaryName, String beneficiaryIban, String beneficiaryBic, String purpose, String endToEndId, LocalDate executionDate, String originalMessageId) {
        var account=accounts.findById(iban).orElseThrow(() -> new IllegalArgumentException("Unknown IBAN: " + iban));
        if(!account.isActive())throw new IllegalArgumentException("Belastungskonto ist deaktiviert");
        if (amount == null || amount.signum() <= 0) throw new IllegalArgumentException("Amount must be positive");
        if (beneficiaryName == null || beneficiaryName.isBlank() || purpose == null || purpose.isBlank()) throw new IllegalArgumentException("beneficiaryName and purpose are required");
        PaymentOrder order=new PaymentOrder(iban, amount.setScale(2), account.getCurrency(), beneficiaryName, beneficiaryIban, beneficiaryBic, purpose, endToEndId, executionDate == null ? LocalDate.now() : executionDate);
        order.setOriginalMessageId(originalMessageId);
        order=orders.save(order);
        history.record(order.getId(),"CREATED","Auftrag angelegt und Grunddaten geprüft");
        return order;
    }
    public List<PaymentOrder> all() { return orders.findAllByOrderByCreatedAtDesc(); }
    public List<PaymentSignature> signatures(Long orderId) { return signatures.findByPaymentOrderIdOrderBySignedAt(orderId); }
    public Detail detail(Long orderId) {
        PaymentOrder order=orders.findById(orderId).orElseThrow(()->new IllegalArgumentException("Unknown payment order: "+orderId));
        String source=order.getSubmissionId()!=null&&order.getSubmissionId().startsWith("VEU-")?"Admin-GUI → EBICS-VEU":
            order.getSubmissionId()!=null||submissions.findByOrderId(orderId).isPresent()?"EBICS-Client (BTU / SCT)":"Admin-GUI";
        return new Detail(order,signatures(orderId),source,history.forOrder(orderId));
    }
    public List<PaymentOrder> forPartner(String partnerId, LocalDate from, LocalDate to) {
        return orders.findAllByOrderByCreatedAtDesc().stream()
                .filter(order -> accounts.findById(order.getIban()).map(account -> partnerId.equals(account.getPartnerId())).orElse(false))
                .filter(order -> !order.getExecutionDate().isBefore(from) && !order.getExecutionDate().isAfter(to))
                .toList();
    }
    @Transactional public List<PaymentOrder> retrieveStatusReports(String partnerId, LocalDate from, LocalDate to) {
        List<PaymentOrder> reports=forPartner(partnerId, from, to);
        return reports;
    }
    @Transactional public PaymentOrder sign(Long orderId, String userId) {
        PaymentOrder order = orders.findLockedById(orderId).orElseThrow(() -> new IllegalArgumentException("Unknown payment order: " + orderId));
        if (order.getStatus() != PaymentOrderStatus.PENDING_SIGNATURES) throw new IllegalArgumentException("Payment order is already approved");
        var participant = participants.findById(userId).orElseThrow(() -> new IllegalArgumentException("Unknown UserID: " + userId));
        if (!participant.isActive()) throw new IllegalArgumentException("User is inactive: " + userId);
        var account = accounts.findById(order.getIban()).orElseThrow();
        if (!account.getPartnerId().equals(participant.getPartnerId())) throw new IllegalArgumentException("User does not belong to the account's partner");
        if (signatures.existsByPaymentOrderIdAndUserId(orderId, userId)) throw new IllegalArgumentException("User has already signed this payment order");
        String signatureClass = authorizations.forAccount(userId, order.getIban()).getSignatureClass();
        if ("T".equals(signatureClass)) throw new IllegalArgumentException("Technical users may not approve payments");
        signatures.save(new PaymentSignature(orderId, userId, signatureClass));
        history.record(orderId,"SIGNED","Freigabe durch "+userId+" ("+signatureClass+")");
        Set<String> collected = new HashSet<>(signatures(orderId).stream().map(PaymentSignature::getSignatureClass).toList());
        if (collected.contains("E") || (collected.contains("A") && collected.contains("B"))) {
            if (order.getExecutionDate().isAfter(LocalDate.now())) {order.schedule();history.record(orderId,"SCHEDULED","Ausführung am "+order.getExecutionDate());} else book(order);
        }
        history.finish(order.getSubmissionId());
        return order;
    }
    public int bookDuePayments() {
        int booked=0;
        for(PaymentOrder candidate:orders.findAllByOrderByCreatedAtDesc()) {
            if(candidate.getStatus()!=PaymentOrderStatus.SCHEDULED||candidate.getExecutionDate().isAfter(LocalDate.now()))continue;
            try{
                Boolean result=new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(status->{
                    PaymentOrder order=orders.findLockedById(candidate.getId()).orElseThrow();
                    if(order.getStatus()!=PaymentOrderStatus.SCHEDULED||order.getExecutionDate().isAfter(LocalDate.now()))return false;
                    book(order);return true;
                });
                if(Boolean.TRUE.equals(result))booked++;
            }catch(RuntimeException e){history.bookingFailed(candidate.getId(),e.getMessage());}
        }
        return booked;
    }
    private void book(PaymentOrder order) {
            order.approve();
            var account=accounts.findById(order.getIban()).orElseThrow();
            if(!account.isActive())throw new IllegalArgumentException("Belastungskonto ist deaktiviert");
            transactions.addDebit("PAY-" + order.getId(), order.getIban(), order.getExecutionDate(), order.getAmount(), order.getBeneficiaryName(), order.getBeneficiaryIban(), order.getBeneficiaryBic(), order.getPurpose(), order.getEndToEndId());
            history.record(order.getId(),"BOOKED","Belastung gebucht: PAY-"+order.getId());
            // A transfer to another account hosted by this test bank is an internal booking:
            // book the matching credit immediately so both CAMT.053 statements agree. Foreign
            // recipients remain an outward payment. Cross-currency internal transfers require
            // an FX workflow and are deliberately not fabricated as a 1:1 credit.
            (order.getBeneficiaryIban()==null?Optional.<Account>empty():accounts.findById(order.getBeneficiaryIban()))
                    .filter(Account::isActive)
                    .ifPresent(beneficiary -> {transactions.addCredit(
                            "PAY-" + order.getId() + "-CREDIT",
                            beneficiary.getIban(), order.getExecutionDate(), convertedAmount(order.getAmount(), order.getCurrency(), beneficiary.getCurrency()),
                            account.getAccountHolder(), account.getIban(), account.getBic(),
                            order.getPurpose() + fxNote(order.getCurrency(), beneficiary.getCurrency()), order.getEndToEndId());
                        history.record(order.getId(),"CREDIT_BOOKED","Interne Gutschrift: PAY-"+order.getId()+"-CREDIT");});
    }
    private static BigDecimal convertedAmount(BigDecimal amount,String from,String to) { if(from.equals(to)) return amount; if("EUR".equals(from)&&"USD".equals(to)) return amount.multiply(new BigDecimal("1.10")).setScale(2,java.math.RoundingMode.HALF_UP); if("USD".equals(from)&&"EUR".equals(to)) return amount.divide(new BigDecimal("1.10"),2,java.math.RoundingMode.HALF_UP); throw new IllegalArgumentException("No test FX rate for "+from+"/"+to); }
    private static String fxNote(String from,String to) { return from.equals(to)?"":" (Test-FX "+from+"/"+to+")"; }
}
