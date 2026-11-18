package de.ostms.evils.ebics.persistence;
import de.ostms.evils.ebics.domain.PaymentEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface PaymentEventRepository extends JpaRepository<PaymentEvent,String>{
    List<PaymentEvent> findByPaymentOrderIdOrderByOccurredAtAsc(Long orderId);
    List<PaymentEvent> findAllByOrderByOccurredAtAsc();
}
