package de.ostms.evils.ebics.persistence;

import de.ostms.evils.ebics.domain.ParticipantKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ParticipantKeyRepository extends JpaRepository<ParticipantKey, Long> {
    List<ParticipantKey> findByUserIdOrderByReceivedAtDesc(String userId);

    long deleteByUserId(String userId);

    Optional<ParticipantKey> findFirstByUserIdAndKeyTypeOrderByReceivedAtDesc(String userId, String keyType);
}
