package de.ostms.evils.ebics.persistence;

import de.ostms.evils.ebics.domain.ProtocolLogEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ProtocolLogEntryRepository extends JpaRepository<ProtocolLogEntry, Long> {
    List<ProtocolLogEntry> findTop500ByHostIdOrderByReceivedAtDesc(String hostId);
}
