package de.ostms.evils.ebics.persistence;
import de.ostms.evils.ebics.domain.Participant;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface ParticipantRepository extends JpaRepository<Participant, String> {
    List<Participant> findAllByHostId(String hostId);
    List<Participant> findAllByHostIdIsNull();
}
