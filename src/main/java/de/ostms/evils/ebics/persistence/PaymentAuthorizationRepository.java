package de.ostms.evils.ebics.persistence;

import de.ostms.evils.ebics.domain.PaymentAuthorization;
import de.ostms.evils.ebics.domain.PaymentAuthorizationId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PaymentAuthorizationRepository extends JpaRepository<PaymentAuthorization, PaymentAuthorizationId> {
    List<PaymentAuthorization> findByUserId(String userId);
    void deleteByUserId(String userId);
}
