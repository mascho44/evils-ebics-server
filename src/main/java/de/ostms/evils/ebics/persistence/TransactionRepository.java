package de.ostms.evils.ebics.persistence;
import de.ostms.evils.ebics.domain.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.List;
public interface TransactionRepository extends JpaRepository<Transaction, String> {
    List<Transaction> findByIbanAndBookingDateBetweenOrderByBookingDateAscTransactionIdAsc(String iban, LocalDate from, LocalDate to);
    List<Transaction> findByIbanAndBookingDateBeforeOrderByBookingDateAscTransactionIdAsc(String iban, LocalDate before);
    List<Transaction> findAllByOrderByBookingDateAscTransactionIdAsc();
    long deleteByIban(String iban);
}
