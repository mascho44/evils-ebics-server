package de.ostms.evils.ebics.persistence;
import de.ostms.evils.ebics.domain.PaymentOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface PaymentOrderRepository extends JpaRepository<PaymentOrder, Long> {
    List<PaymentOrder> findAllByOrderByCreatedAtDesc();
    boolean existsByIban(String iban);
    List<PaymentOrder> findBySubmissionIdOrderById(String submissionId);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select p from payment_orders p where p.id = :id")
    java.util.Optional<PaymentOrder> findLockedById(@org.springframework.data.repository.query.Param("id") Long id);
}
