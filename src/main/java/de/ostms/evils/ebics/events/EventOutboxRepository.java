package de.ostms.evils.ebics.events;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.*;
public interface EventOutboxRepository extends JpaRepository<EventOutbox,String> {
    List<EventOutbox> findTop20ByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(String status,Instant now);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from EventOutbox e where e.id=:id")
    Optional<EventOutbox> findLocked(@Param("id") String id);
    long countByStatus(String status);
    List<EventOutbox> findByStatus(String status,org.springframework.data.domain.Pageable pageable);
}
