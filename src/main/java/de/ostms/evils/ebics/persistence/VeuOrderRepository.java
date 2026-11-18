package de.ostms.evils.ebics.persistence;
import de.ostms.evils.ebics.domain.VeuOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface VeuOrderRepository extends JpaRepository<VeuOrder,Long> {
    Optional<VeuOrder> findByOrderId(String orderId);
    List<VeuOrder> findByPartnerIdOrderByCreatedAtAsc(String partnerId);
}
