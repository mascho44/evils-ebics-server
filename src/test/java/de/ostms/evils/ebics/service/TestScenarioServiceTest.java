package de.ostms.evils.ebics.service;
import de.ostms.evils.ebics.domain.*;
import de.ostms.evils.ebics.persistence.*;
import de.ostms.evils.ebics.client.SepaPayment;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest @ActiveProfiles("test")
class TestScenarioServiceTest {
    @Autowired TestScenarioService scenarios;@Autowired CustomerRepository customers;@Autowired AccountRepository accounts;@Autowired PaymentOrderRepository orders;
    @Test void fixturesAreAdditiveAndCannotBeCreatedTwice(){
        customers.save(new Customer("EVILSBANK","SCENARIO-TEST","Tests",true));long before=accounts.count();
        var result=scenarios.create("EVILSBANK","SCENARIO-TEST","EUR_USD");
        assertThat(accounts.count()).isEqualTo(before+2);
        result.ibans().forEach(SepaPayment::iban);
        assertThat(accounts.findById(result.ibans().get(1)).orElseThrow().getCurrency()).isEqualTo("USD");
        assertThat(orders.findById(result.paymentIds().getFirst()).orElseThrow().getStatus()).isEqualTo(PaymentOrderStatus.PENDING_SIGNATURES);
        assertThatThrownBy(()->scenarios.create("EVILSBANK","SCENARIO-TEST","EUR_USD")).isInstanceOf(IllegalArgumentException.class);
        assertThat(accounts.count()).isEqualTo(before+2);
    }
}
