package de.ostms.evils.ebics.persistence;
import de.ostms.evils.ebics.domain.HacAcknowledgement;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface HacAcknowledgementRepository extends JpaRepository<HacAcknowledgement, Long> { List<HacAcknowledgement> findAllByOrderByReceivedAtDesc(); }
