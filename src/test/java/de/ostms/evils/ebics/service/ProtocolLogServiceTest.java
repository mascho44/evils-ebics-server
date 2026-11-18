package de.ostms.evils.ebics.service;

import de.ostms.evils.ebics.domain.ProtocolLogEntry;
import de.ostms.evils.ebics.protocol.EbicsRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class ProtocolLogServiceTest {
    @Autowired ProtocolLogService service;
    @Autowired BankService bankService;

    @Test
    void persistsOnlyProtocolMetadataForAcceptedRequests() {
        service.accepted(new EbicsRequest("H005", "EVILSBANK", "TEST001", "USER001", "HPD", "LOG-1", "<secret/>"));
        var entry = service.recentForTenant("EVILSBANK").getFirst();
        assertThat(entry.getOrderType()).isEqualTo("HPD");
        assertThat(entry.getOutcome()).isEqualTo("ACCEPTED: Abgerufen: Bankparameter · Auftrag: LOG-1");
        assertThat(entry.getOutcome()).doesNotContain("<secret/>");
        assertThat(entry.getOrderId()).isEqualTo("LOG-1");
    }

    /** Regression fuer den Bug, dass die Protokoll-Ansicht im Admin-UI das "Mandant"-Dropdown
     * ignorierte: recentForTenant muss ausschliesslich Eintraege des angefragten hostId liefern,
     * nie andere Mandanten mit einmischen. */
    @Test
    void recentForTenantDoesNotLeakOtherTenantsEntries() {
        bankService.createBank("OTHERTENANT", "Other Tenant Bank", "H005", "OTHRDEFFXXX");
        service.accepted(new EbicsRequest("H005", "EVILSBANK", "TEST001", "USER001", "HPD", "OWN-LOG", "<secret/>"));
        service.accepted(new EbicsRequest("H005", "OTHERTENANT", "TESTX", "USERX", "HPD", "OTHER-LOG", "<secret/>"));

        var evilsbankEntries = service.recentForTenant("EVILSBANK");
        var otherTenantEntries = service.recentForTenant("OTHERTENANT");

        assertThat(evilsbankEntries).extracting(ProtocolLogEntry::getOrderId).contains("OWN-LOG").doesNotContain("OTHER-LOG");
        assertThat(otherTenantEntries).extracting(ProtocolLogEntry::getOrderId).contains("OTHER-LOG").doesNotContain("OWN-LOG");
    }
}
