package de.ostms.evils.ebics.persistence;
import de.ostms.evils.ebics.domain.Bank;
import org.springframework.data.jpa.repository.JpaRepository;
public interface BankRepository extends JpaRepository<Bank, String> { }
