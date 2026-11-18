package de.ostms.evils.ebics.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** Ein per Admin-UI erzeugtes MT700-/MT707-/MT710-Dokument (Akkreditiv-Eroeffnung/-Aenderung/
 * Drittbank-Avisierung), einem Kunden (PartnerID) zugeordnet, damit es spaeter per EBICS BTD
 * abgerufen werden kann (siehe LcDocumentService, EbicsController). Anders als camt.053 (live aus
 * Buchungen abgeleitet, siehe StatementService) gibt es hier keine zugrundeliegende Ledger-Quelle -
 * der generierte Rohtext IST das Dokument und muss deshalb gespeichert werden. abgerufenAm
 * verfolgt lediglich informativ (Admin-UI), ob/wann ein Dokument zuletzt per EBICS ausgeliefert
 * wurde - verhindert keinen wiederholten Abruf, ein EBICS-Client darf dieselbe Nachricht jederzeit
 * erneut abrufen. */
@Entity(name = "lc_documents")
public class LcDocument {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private String hostId; private String partnerId;
    private String messageType; private String referenz;
    @Lob private String rohtext;
    private LocalDateTime erzeugtAm; private LocalDateTime abgerufenAm;

    protected LcDocument() { }

    public LcDocument(String hostId, String partnerId, String messageType, String referenz, String rohtext) {
        this.hostId = hostId;
        this.partnerId = partnerId;
        this.messageType = messageType;
        this.referenz = referenz;
        this.rohtext = rohtext;
        this.erzeugtAm = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public String getHostId() { return hostId; }
    public String getPartnerId() { return partnerId; }
    public String getMessageType() { return messageType; }
    public String getReferenz() { return referenz; }
    public String getRohtext() { return rohtext; }
    public LocalDateTime getErzeugtAm() { return erzeugtAm; }
    public LocalDateTime getAbgerufenAm() { return abgerufenAm; }

    public void markiereAbgerufen() {
        this.abgerufenAm = LocalDateTime.now();
    }
}
