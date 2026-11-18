package de.ostms.evils.ebics.camt;

import de.ostms.evils.ebics.domain.PaymentOrder;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class Pain002GeneratorTest {
    @Test
    void reportsBookedPaymentWithOriginalReferences() {
        PaymentOrder order = new PaymentOrder("DE00123456789012345678", new BigDecimal("123.00"), "EUR",
                "Max Muster", "DE00987654321098765432", null, "Testzahlung", "E2E-123", LocalDate.now());
        order.setOriginalMessageId("PAIN-123");
        order.approve();

        String xml = new Pain002Generator().generate(List.of(order));
        new de.ostms.evils.ebics.service.StatusSchemaValidator().validate(xml);

        assertTrue(xml.contains("pain.002.001.03"));
        assertTrue(xml.contains("<OrgnlMsgId>PAIN-123</OrgnlMsgId>"));
        assertTrue(xml.contains("<OrgnlEndToEndId>E2E-123</OrgnlEndToEndId>"));
        assertTrue(xml.contains("<TxSts>ACSC</TxSts>"));
    }
    @Test void refusesMultipleOriginalMessagesInOneDocument(){
        var a=new PaymentOrder("DE89370400440532013000",new BigDecimal("1.00"),"EUR","Test",null,null,"Purpose","A",LocalDate.now());
        var b=new PaymentOrder("DE89370400440532013000",new BigDecimal("2.00"),"EUR","Test",null,null,"Purpose","B",LocalDate.now());
        a.setOriginalMessageId("ONE");b.setOriginalMessageId("TWO");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,()->new Pain002Generator().generate(List.of(a,b)));
    }
}
