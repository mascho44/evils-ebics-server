package de.ostms.evils.ebics.service;
import de.ostms.evils.ebics.domain.*;
import de.ostms.evils.ebics.persistence.*;
import de.ostms.evils.ebics.util.IbanGenerator;
import jakarta.persistence.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** Additive, deterministic fixtures; never resets or overwrites existing customer data. */
@Service
public class TestScenarioService {
    private final EntityManager em;private final AccountRepository accounts;private final PaymentOrderService payments;private final TransactionService transactions;
    public TestScenarioService(EntityManager em,AccountRepository accounts,PaymentOrderService payments,TransactionService transactions){this.em=em;this.accounts=accounts;this.payments=payments;this.transactions=transactions;}
    public record Result(String scenario,List<String> ibans,List<Long> paymentIds,String message){}
    @Transactional public Result create(String hostId,String partnerId,String scenario){
        if(!Set.of("EUR_USD","INTERNAL_TRANSFER","SCHEDULED").contains(scenario))throw new IllegalArgumentException("Unknown scenario");
        Customer customer=em.find(Customer.class,partnerId,LockModeType.PESSIMISTIC_WRITE);
        if(customer==null||!hostId.equals(customer.getHostId()))throw new IllegalArgumentException("Unknown PartnerID");
        String prefix="SCENARIO-"+partnerId+"-"+scenario;
        String first=IbanGenerator.vonSeed(prefix+"-1"),second=IbanGenerator.vonSeed(prefix+"-2");
        if(accounts.existsById(first)||accounts.existsById(second))throw new IllegalArgumentException("Dieses Szenario wurde bereits angelegt. Vorhandene Testdaten bleiben unverändert.");
        var today=LocalDate.now();
        accounts.save(new Account(hostId,first,"EVILDEFFXXX",partnerId,"Test "+scenario+" EUR",new BigDecimal("10000.00"),today,"EUR",true));
        accounts.save(new Account(hostId,second,"EVILDEFFXXX",partnerId,"Test "+scenario+" Empfänger",BigDecimal.ZERO,today,scenario.equals("EUR_USD")?"USD":"EUR",true));
        transactions.addCredit(prefix+"-START",first,today,new BigDecimal("250.00"),"Testszenario",null,null,prefix,"SCENARIO-START");
        var order=payments.create(first,new BigDecimal("100.00"),"Testempfänger",second,"EVILDEFFXXX",prefix,"SCENARIO-E2E",scenario.equals("SCHEDULED")?today.plusDays(1):today);
        return new Result(scenario,List.of(first,second),List.of(order.getId()),"Zwei neue Testkonten und ein offener Zahlungsauftrag angelegt. Im Zahlungsverkehr mit einem berechtigten Teilnehmer freigeben; bestehende Konten wurden nicht verändert.");
    }
}
