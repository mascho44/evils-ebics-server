package de.ostms.evils.ebics.persistence;

import de.ostms.evils.ebics.domain.LcDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LcDocumentRepository extends JpaRepository<LcDocument, Long> {
    /** Fuer den EBICS-BTD-Abruf (siehe LcDocumentService.abrufen): das zuletzt generierte Dokument
     * dieses Typs fuer den anfragenden Kunden - genau wie ein Kontoauszug-Abruf immer den aktuellen
     * Stand liefert, nicht eine Historie aller je erzeugten Versionen. */
    Optional<LcDocument> findFirstByHostIdAndPartnerIdAndMessageTypeOrderByErzeugtAmDesc(
            String hostId, String partnerId, String messageType);
}
