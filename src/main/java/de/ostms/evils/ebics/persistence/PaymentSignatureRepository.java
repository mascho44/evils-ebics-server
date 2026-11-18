package de.ostms.evils.ebics.persistence;
import de.ostms.evils.ebics.domain.PaymentSignature;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface PaymentSignatureRepository extends JpaRepository<PaymentSignature, Long> {
    List<PaymentSignature> findByPaymentOrderIdOrderBySignedAt(Long paymentOrderId);
    boolean existsByPaymentOrderIdAndUserId(Long paymentOrderId, String userId);
    boolean existsByUserId(String userId);
}
