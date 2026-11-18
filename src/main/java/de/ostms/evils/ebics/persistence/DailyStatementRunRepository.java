package de.ostms.evils.ebics.persistence;

import de.ostms.evils.ebics.domain.DailyStatementRun;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DailyStatementRunRepository extends JpaRepository<DailyStatementRun, String> { }
