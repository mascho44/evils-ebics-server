package de.ostms.evils.ebics.persistence;

import de.ostms.evils.ebics.domain.TenantRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TenantRequestRepository extends JpaRepository<TenantRequest, Long> {
    List<TenantRequest> findAllByOrderByRequestedAtDesc();
    List<TenantRequest> findAllByStatusOrderByRequestedAtDesc(String status);
    boolean existsByHostIdAndStatus(String hostId, String status);
    boolean existsByAdminUsernameAndStatus(String adminUsername, String status);
}
