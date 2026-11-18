package de.ostms.evils.ebics.persistence;
import de.ostms.evils.ebics.domain.ReportDownload;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.*;
public interface ReportDownloadRepository extends JpaRepository<ReportDownload,String>{
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select d from ReportDownload d where d.id=:id")
    Optional<ReportDownload> findLocked(@Param("id") String id);
    List<ReportDownload> findByKindAndReceiptCode(String kind,Integer code);
}
