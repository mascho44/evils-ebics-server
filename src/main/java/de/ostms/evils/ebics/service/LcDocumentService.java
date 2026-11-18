package de.ostms.evils.ebics.service;

import de.ostms.evils.ebics.domain.LcDocument;
import de.ostms.evils.ebics.persistence.LcDocumentRepository;
import de.ostms.evils.ebics.protocol.EbicsProtocolException;
import de.ostms.evils.ebics.swift.Mt700Generator;
import de.ostms.evils.ebics.swift.Mt707Generator;
import de.ostms.evils.ebics.swift.Mt710Generator;
import de.ostms.evils.ebics.swift.Mt760Generator;
import de.ostms.evils.ebics.swift.SwiftNachricht;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Bindeglied zwischen den reinen MT7xx-Generatoren (Mt700Generator/Mt707Generator/Mt710Generator/
 * Mt760Generator - zustandslos, kein DB-Zugriff) und ihrer Persistenz (LcDocument/
 * LcDocumentRepository): jedes ueber das Admin-UI generierte Dokument wird hier sofort einem Kunden
 * (PartnerID) zugeordnet gespeichert, damit ein EBICS-Client es anschliessend per BTD abrufen kann
 * (siehe EbicsController) - vorher existierten diese Nachrichten nur fluechtig als API-Antwort ans
 * Admin-UI  ("Akkreditiv Abruf und Testdatengenerator" - der Generator existierte
 * bereits, der Abruf per EBICS fehlte). */
@Service
public class LcDocumentService {

    private final LcDocumentRepository repository;
    private final Mt700Generator mt700Generator;
    private final Mt707Generator mt707Generator;
    private final Mt710Generator mt710Generator;
    private final Mt760Generator mt760Generator;

    public LcDocumentService(LcDocumentRepository repository, Mt700Generator mt700Generator,
                              Mt707Generator mt707Generator, Mt710Generator mt710Generator,
                              Mt760Generator mt760Generator) {
        this.repository = repository;
        this.mt700Generator = mt700Generator;
        this.mt707Generator = mt707Generator;
        this.mt710Generator = mt710Generator;
        this.mt760Generator = mt760Generator;
    }

    @Transactional
    public SwiftNachricht mt700(String hostId, String partnerId, String applicant, String beneficiary) {
        return speichern(hostId, partnerId, mt700Generator.generieren(applicant, beneficiary));
    }

    @Transactional
    public SwiftNachricht mt707(String hostId, String partnerId, String lcReferenz) {
        return speichern(hostId, partnerId, mt707Generator.generieren(lcReferenz));
    }

    @Transactional
    public SwiftNachricht mt710(String hostId, String partnerId, String issuingBank, String applicant, String beneficiary) {
        return speichern(hostId, partnerId, mt710Generator.generieren(issuingBank, applicant, beneficiary));
    }

    @Transactional
    public SwiftNachricht mt760(String hostId, String partnerId, String applicant, String beneficiary) {
        return speichern(hostId, partnerId, mt760Generator.generieren(applicant, beneficiary));
    }

    private SwiftNachricht speichern(String hostId, String partnerId, SwiftNachricht nachricht) {
        repository.save(new LcDocument(hostId, partnerId, nachricht.messageType(), nachricht.referenz(), nachricht.rohtext()));
        return nachricht;
    }

    /** Fuer den EBICS-BTD-Abruf (siehe EbicsController): liefert das zuletzt fuer diesen Kunden
     * generierte Dokument des angefragten Typs und markiert es als abgerufen. Wirft, falls noch nie
     * eines generiert wurde - anders als camt.053 (immer verfuegbar, notfalls leer) gibt es fuer
     * MT7xx keine Ledger-Quelle, aus der sich mangels Dokument ersatzweise etwas ableiten liesse. */
    @Transactional
    public String abrufen(String hostId, String partnerId, String messageType) {
        LcDocument dokument = repository
                .findFirstByHostIdAndPartnerIdAndMessageTypeOrderByErzeugtAmDesc(hostId, partnerId, messageType)
                .orElseThrow(() -> new EbicsProtocolException(
                        "Noch kein " + messageType + "-Dokument fuer PartnerID " + partnerId
                                + " generiert - zuerst ueber das Admin-UI erzeugen."));
        dokument.markiereAbgerufen();
        return dokument.getRohtext();
    }
}
