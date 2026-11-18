package de.ostms.evils.ebics.persistence;
import de.ostms.evils.ebics.domain.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface AccountRepository extends JpaRepository<Account, String> {
    List<Account> findAllByHostId(String hostId);
    List<Account> findAllByHostIdIsNull();
}
