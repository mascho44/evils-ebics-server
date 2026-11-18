package de.ostms.evils.ebics.persistence;
import de.ostms.evils.ebics.domain.AdminUser;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface AdminUserRepository extends JpaRepository<AdminUser, String> {
    long countByRoleAndActiveTrue(String role);
    long countByHostIdAndRoleAndActiveTrue(String hostId, String role);
    /** Spring Data's "By hostId" comparison generates "hostId = ?1", which never matches NULL
     * rows in SQL - needed separately for counting active SUPERADMINs (hostId == null). */
    long countByHostIdIsNullAndRoleAndActiveTrue(String role);
    List<AdminUser> findAllByHostId(String hostId);
}
