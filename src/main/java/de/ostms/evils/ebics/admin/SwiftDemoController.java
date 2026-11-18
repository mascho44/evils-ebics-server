package de.ostms.evils.ebics.admin;

import de.ostms.evils.ebics.domain.Customer;
import de.ostms.evils.ebics.service.BankService;
import de.ostms.evils.ebics.service.LcDocumentService;
import de.ostms.evils.ebics.swift.SwiftNachricht;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Generiert MT700-/MT707-/MT710-/MT760-Demo-Nachrichten (Dokumentenakkreditiv-Eroeffnung/
 * -Aenderung, Drittbank-Avisierung, Bankgarantie) fuer Vorfuehr-/Testzwecke - gleiches Prinzip wie
 * AdminController.generateIban: schnell eine realistische, direkt verwendbare Beispielnachricht
 * statt eine von Hand zu tippen. Applicant/Beneficiary werden, falls vorhanden, aus dem Kundenstamm
 * des Mandanten gezogen (siehe beteiligteAusKundenstamm) statt reiner Platzhalter - macht die
 * generierte Nachricht in einer Demo mit bereits angelegten Kunden gleich glaubwuerdiger. Jedes
 * generierte Dokument wird ueber LcDocumentService sofort dem ersten aktiven Kunden zugeordnet
 * gespeichert (optional per partnerId-Parameter ueberschreibbar), damit es anschliessend auch per
 * EBICS BTD abgerufen werden kann (siehe EbicsController). */
@RestController
@RequestMapping("/admin/api/swift")
public class SwiftDemoController {

    private final BankService bankService;
    private final LcDocumentService lcDocumentService;

    public SwiftDemoController(BankService bankService, LcDocumentService lcDocumentService) {
        this.bankService = bankService;
        this.lcDocumentService = lcDocumentService;
    }

    @GetMapping("/mt700")
    public SwiftNachricht mt700(@RequestHeader(value = "X-Tenant-Host-Id", required = false) String tenantHeader,
                                @RequestParam(required = false) String partnerId) {
        String hostId = TenantResolver.effectiveHostId(tenantHeader, bankService);
        Beteiligte beteiligte = beteiligteAusKundenstamm(hostId);
        return lcDocumentService.mt700(hostId, partnerIdOderErster(hostId, partnerId),
                beteiligte.applicant(), beteiligte.beneficiary());
    }

    /** lcReferenz optional - ohne sie (z.B. wenn der Nutzer vorher kein MT700 generiert hat) wird
     * eine frische, plausibel aussehende Referenz erzeugt; typischerweise uebergibt das Admin-UI
     * aber die Referenz der zuletzt generierten MT700, damit die Aenderung sichtbar dazugehoert. */
    @GetMapping("/mt707")
    public SwiftNachricht mt707(@RequestHeader(value = "X-Tenant-Host-Id", required = false) String tenantHeader,
                                 @RequestParam(required = false) String lcReferenz,
                                 @RequestParam(required = false) String partnerId) {
        String hostId = TenantResolver.effectiveHostId(tenantHeader, bankService);
        return lcDocumentService.mt707(hostId, partnerIdOderErster(hostId, partnerId), lcReferenz);
    }

    /** issuingBank optional - ohne sie wird ein Platzhaltername verwendet; das Admin-UI kann hier
     * z.B. den Namen einer anderen, in einem anderen Mandanten gefuehrten Bank uebergeben. */
    @GetMapping("/mt710")
    public SwiftNachricht mt710(@RequestHeader(value = "X-Tenant-Host-Id", required = false) String tenantHeader,
                                 @RequestParam(required = false) String issuingBank,
                                 @RequestParam(required = false) String partnerId) {
        String hostId = TenantResolver.effectiveHostId(tenantHeader, bankService);
        Beteiligte beteiligte = beteiligteAusKundenstamm(hostId);
        String issuer = (issuingBank == null || issuingBank.isBlank()) ? "Demo Issuing Bank AG" : issuingBank;
        return lcDocumentService.mt710(hostId, partnerIdOderErster(hostId, partnerId), issuer,
                beteiligte.applicant(), beteiligte.beneficiary());
    }

    @GetMapping("/mt760")
    public SwiftNachricht mt760(@RequestHeader(value = "X-Tenant-Host-Id", required = false) String tenantHeader,
                                @RequestParam(required = false) String partnerId) {
        String hostId = TenantResolver.effectiveHostId(tenantHeader, bankService);
        Beteiligte beteiligte = beteiligteAusKundenstamm(hostId);
        return lcDocumentService.mt760(hostId, partnerIdOderErster(hostId, partnerId),
                beteiligte.applicant(), beteiligte.beneficiary());
    }

    private record Beteiligte(String applicant, String beneficiary) {
    }

    private Beteiligte beteiligteAusKundenstamm(String hostId) {
        List<Customer> kunden = bankService.customersForTenant(hostId).stream().filter(Customer::isActive).toList();
        String applicant = !kunden.isEmpty() ? kunden.get(0).getName() : "Demo Applicant GmbH";
        String beneficiary = kunden.size() > 1 ? kunden.get(1).getName() : "Demo Beneficiary Ltd";
        return new Beteiligte(applicant, beneficiary);
    }

    /** Ohne expliziten partnerId-Parameter wird der erste aktive Kunde des Mandanten als Empfaenger
     * des generierten Dokuments angenommen (derselbe, dessen Name bereits als Applicant im Text
     * landet, siehe beteiligteAusKundenstamm) - ein Mandant ohne jeden Kunden kann kein Dokument
     * abrufbar generieren, das waere per EBICS ohnehin niemandem zustellbar. */
    private String partnerIdOderErster(String hostId, String partnerId) {
        if (partnerId != null && !partnerId.isBlank()) {
            return partnerId;
        }
        return bankService.customersForTenant(hostId).stream().filter(Customer::isActive).findFirst()
                .map(Customer::getPartnerId)
                .orElseThrow(() -> new IllegalStateException(
                        "Mandant " + hostId + " hat keinen aktiven Kunden - ein generiertes Dokument waere per EBICS niemandem zustellbar."));
    }
}
