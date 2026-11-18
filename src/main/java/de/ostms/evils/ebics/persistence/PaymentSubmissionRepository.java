package de.ostms.evils.ebics.persistence;
import de.ostms.evils.ebics.domain.PaymentSubmission;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
public interface PaymentSubmissionRepository extends JpaRepository<PaymentSubmission,String> {
    Optional<PaymentSubmission> findByOrderId(Long orderId);
}
