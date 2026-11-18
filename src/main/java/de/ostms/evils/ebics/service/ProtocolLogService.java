package de.ostms.evils.ebics.service;

import de.ostms.evils.ebics.domain.ProtocolLogEntry;
import de.ostms.evils.ebics.persistence.ProtocolLogEntryRepository;
import de.ostms.evils.ebics.protocol.EbicsRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
public class ProtocolLogService {
    private final ProtocolLogEntryRepository entries;
    public ProtocolLogService(ProtocolLogEntryRepository entries) { this.entries = entries; }
    @Transactional public void accepted(EbicsRequest request) {
        entries.save(new ProtocolLogEntry(request.hostId(), request.partnerId(), request.userId(), request.orderType(), request.orderId(), request.version(), "ACCEPTED: " + describe(request)));
    }
    @Transactional public void rejected(EbicsRequest request, String reason) {
        String outcome = "REJECTED: " + describe(request);
        if (reason != null && !reason.isBlank()) {
            outcome += " · " + reason.substring(0, Math.min(reason.length(), 140));
        }
        entries.save(new ProtocolLogEntry(request.hostId(), request.partnerId(), request.userId(), request.orderType(), request.orderId(), request.version(), outcome));
    }
    @Transactional public void uploadAccepted(String hostId, String partnerId, String userId, String orderId) {
        entries.save(new ProtocolLogEntry(hostId, partnerId, userId, "BTU", orderId, "H005", "ACCEPTED: Gesendet: SEPA-Zahlungsauftrag (pain.001) · Auftragsnummer: " + safe(orderId)));
    }
    @Transactional public void uploadRejected(String hostId, String partnerId, String userId, String category) {
        entries.save(new ProtocolLogEntry(hostId, partnerId, userId, "BTU", null, "H005", "REJECTED: Gesendet: SEPA-Zahlungsauftrag (pain.001) · Prüfung: " + safe(category)));
    }
    public List<ProtocolLogEntry> recentForTenant(String hostId) { return entries.findTop500ByHostIdOrderByReceivedAtDesc(hostId); }

    private static String describe(EbicsRequest request) {
        String order = request.orderType() == null ? "unbekannter EBICS-Auftrag" : request.orderType().toUpperCase(java.util.Locale.ROOT);
        String operation = switch (order) {
            case "BTD" -> "Abgerufen: " + document(request.btdServiceName(), request.btdMsgName(), "Kontoauszug (camt.053)");
            case "BTU" -> "Gesendet: SEPA-Zahlungsauftrag (pain.001)";
            case "HPB" -> "Abgerufen: Bankschlüssel";
            case "HPD" -> "Abgerufen: Bankparameter";
            case "HKD" -> "Abgerufen: Teilnehmerparameter";
            case "HTD" -> "Abgerufen: Teilnehmer- und Kontoberechtigungen";
            case "INI" -> "Gesendet: Signaturschlüssel";
            case "HIA" -> "Gesendet: Authentifizierungs- und Verschlüsselungsschlüssel";
            case "HAC" -> "Gesendet: Auftragsbestätigung";
            case "HVZ" -> "Abgerufen: verfügbare Aufträge";
            default -> "Verarbeitet: " + order;
        };
        String detail = operation + " · Auftrag: " + safe(request.orderId());
        return request.transactionPhase() == null || request.transactionPhase().isBlank()
                ? detail : detail + " · Phase: " + phase(request.transactionPhase());
    }

    private static String document(String service, String message, String fallback) {
        if (message == null || message.isBlank()) return fallback;
        return (service == null || service.isBlank()) ? message : service + " / " + message;
    }

    private static String phase(String value) {
        return switch (value) {
            case "Initialisation" -> "Initialisierung";
            case "Transfer" -> "Übertragung";
            case "Receipt" -> "Quittierung";
            default -> value;
        };
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) return "nicht angegeben";
        return value.replaceAll("[^A-Za-z0-9._:/-]", "_").substring(0, Math.min(value.length(), 120));
    }
}
