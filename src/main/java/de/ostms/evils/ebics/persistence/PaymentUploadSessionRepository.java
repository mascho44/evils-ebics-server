package de.ostms.evils.ebics.persistence;

import de.ostms.evils.ebics.domain.PaymentUploadSession;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentUploadSessionRepository extends JpaRepository<PaymentUploadSession, String> { }
