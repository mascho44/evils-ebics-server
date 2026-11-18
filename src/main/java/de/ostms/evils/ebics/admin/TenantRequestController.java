package de.ostms.evils.ebics.admin;

import de.ostms.evils.ebics.domain.TenantRequest;
import de.ostms.evils.ebics.security.EvilsUserDetails;
import de.ostms.evils.ebics.service.TenantRequestService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** SUPERADMIN-only review queue for public tenant signup requests (see TenantSignupController).
 * Restricted to role ADMIN at the URL level (see SecurityConfiguration); the actual "must be a
 * superadmin" check happens here, same pattern as TenantController. */
@RestController
@RequestMapping("/admin/api/tenant-requests")
public class TenantRequestController {

    private final TenantRequestService tenantRequests;

    public TenantRequestController(TenantRequestService tenantRequests) {
        this.tenantRequests = tenantRequests;
    }

    private static void requireSuperadmin(Authentication authentication) {
        if (!(authentication.getPrincipal() instanceof EvilsUserDetails user) || !user.isSuperadmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Nur für Systemadministratoren (SUPERADMIN).");
        }
    }

    @GetMapping
    public List<TenantRequest> pending(Authentication authentication) {
        requireSuperadmin(authentication);
        return tenantRequests.pending();
    }

    public record RejectRequest(String reason) { }

    @PostMapping("/{id}/approve")
    public TenantRequest approve(@PathVariable Long id, Authentication authentication) {
        requireSuperadmin(authentication);
        return tenantRequests.approve(id, authentication.getName());
    }

    @PostMapping("/{id}/reject")
    public TenantRequest reject(@PathVariable Long id, @RequestBody(required = false) RejectRequest body, Authentication authentication) {
        requireSuperadmin(authentication);
        return tenantRequests.reject(id, authentication.getName(), body == null ? null : body.reason());
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public java.util.Map<String, String> invalid(RuntimeException error) {
        return java.util.Map.of("error", error.getMessage());
    }
}
