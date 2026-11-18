package de.ostms.evils.ebics.admin;

import de.ostms.evils.ebics.domain.TenantRequest;
import de.ostms.evils.ebics.service.TenantRequestService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * Public, unauthenticated - anyone with the URL can request a new sandbox tenant, see
 * TenantRequestService.submit. Deliberately under /api/... rather than /admin/api/... so the
 * public/admin boundary is visible in the URL itself, and explicitly permitAll in
 * SecurityConfiguration (this endpoint being reachable without login is intentional, not a gap).
 * Nothing is actually created here - see TenantRequestController for the SUPERADMIN approval step.
 */
@RestController
@RequestMapping("/api/tenant-signup")
public class TenantSignupController {

    private final TenantRequestService tenantRequests;

    public TenantSignupController(TenantRequestService tenantRequests) {
        this.tenantRequests = tenantRequests;
    }

    public record Result(Long id, String status) { }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Result submit(@RequestBody TenantSignupRequest request) {
        TenantRequest created = tenantRequests.submit(request.hostId(), request.hostName(),
                request.protocolVersion(), request.bic(), request.adminUsername(),
                request.adminDisplayName(), request.adminPassword(), request.requesterEmail());
        return new Result(created.getId(), created.getStatus());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public java.util.Map<String, String> invalid(IllegalArgumentException error) {
        return java.util.Map.of("error", error.getMessage());
    }
}
