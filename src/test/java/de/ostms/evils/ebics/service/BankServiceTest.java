package de.ostms.evils.ebics.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class BankServiceTest {

    @Autowired
    private BankService service;

    @Test
    void demoBankExists() {
        assertEquals("EVILSBANK", service.bank("EVILSBANK").hostId());
        assertEquals("H005", service.bank("EVILSBANK").protocolVersion());
    }

    @Test
    void demoCustomerExists() {
        assertTrue(service.findCustomer("EVILSBANK", "TEST001").isPresent());
    }

    @Test
    void demoParticipantExists() {
        var participant = service.findParticipant("USER001");
        assertTrue(participant.isPresent());
        assertEquals("TEST001", participant.get().partnerId());
    }

    @Test
    void demoAccountExists() {
        var account = service.findAccount("DE00123456789012345678");
        assertTrue(account.isPresent());
        assertEquals("EUR", account.get().currency());
    }

    /** The core isolation guarantee multi-tenancy exists to provide: a second tenant's admin
     * must never see or reach the first tenant's data, even via the tenant-scoped lookups. */
    @Test
    void tenantScopedLookupsDontLeakAcrossTenants() {
        service.createBank("OTHERBANK", "Other Test Bank", "H005", "OTHRDEFFXXX");

        assertTrue(service.findCustomer("EVILSBANK", "TEST001").isPresent());
        assertTrue(service.findCustomer("OTHERBANK", "TEST001").isEmpty());

        assertTrue(service.findParticipant("EVILSBANK", "USER001").isPresent());
        assertTrue(service.findParticipant("OTHERBANK", "USER001").isEmpty());

        assertTrue(service.findAccount("EVILSBANK", "DE00123456789012345678").isPresent());
        assertTrue(service.findAccount("OTHERBANK", "DE00123456789012345678").isEmpty());

        assertTrue(service.customersForTenant("OTHERBANK").isEmpty());
        assertTrue(service.customersForTenant("EVILSBANK").stream().anyMatch(c -> "TEST001".equals(c.getPartnerId())));

        service.deleteBank("OTHERBANK");
    }

    @Test
    void generiereUnbenutzteTestIban_liefert_frische_gueltige_iban() {
        String erste = service.generiereUnbenutzteTestIban();
        assertTrue(erste.startsWith("DE"));
        assertTrue(service.findAccount(erste).isEmpty());

        String zweite = service.generiereUnbenutzteTestIban();
        assertNotEquals(erste, zweite, "zwei Aufrufe sollten praktisch nie kollidieren");
    }
}
