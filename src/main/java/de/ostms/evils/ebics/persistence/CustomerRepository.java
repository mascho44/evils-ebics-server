package de.ostms.evils.ebics.persistence;
import de.ostms.evils.ebics.domain.Customer;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface CustomerRepository extends JpaRepository<Customer, String> {
    List<Customer> findAllByHostId(String hostId);
    List<Customer> findAllByHostIdIsNull();
}
