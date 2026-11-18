package de.ostms.evils.ebics.persistence;

import de.ostms.evils.ebics.domain.BankKey;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface BankKeyRepository extends JpaRepository<BankKey, Long> {
    List<BankKey> findAllByOrderByCreatedAtDesc();
    List<BankKey> findByKeyTypeAndActiveTrue(String keyType);
}
