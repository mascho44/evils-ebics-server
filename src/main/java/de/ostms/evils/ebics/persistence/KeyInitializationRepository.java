package de.ostms.evils.ebics.persistence;

import de.ostms.evils.ebics.domain.KeyInitialization;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface KeyInitializationRepository extends JpaRepository<KeyInitialization, Long> {
    List<KeyInitialization> findAllByOrderByReceivedAtDesc();
}
