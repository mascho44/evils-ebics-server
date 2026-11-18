package de.ostms.evils.ebics.admin;

import de.ostms.evils.ebics.domain.ProtocolLogEntry;
import de.ostms.evils.ebics.service.BankService;
import de.ostms.evils.ebics.service.ProtocolLogService;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** Tenant-scoped wie AdminController (siehe TenantResolver.effectiveHostId) - ohne das zeigte die
 * Protokoll-Ansicht immer die neuesten 500 EBICS-Anfragen ueber ALLE Mandanten hinweg, unabhaengig
 * vom "Mandant"-Dropdown im Admin-UI, das jede andere Ansicht (Konten, Zahlungsverkehr, ...) korrekt
 * filtert. */
@RestController
@RequestMapping("/admin/api/protocol-logs")
public class ProtocolLogController {
    private final ProtocolLogService service;
    private final BankService bankService;
    public ProtocolLogController(ProtocolLogService service, BankService bankService) {
        this.service = service;
        this.bankService = bankService;
    }
    @GetMapping
    public List<ProtocolLogEntry> recent(@RequestHeader(value = "X-Tenant-Host-Id", required = false) String tenantHeader) {
        return service.recentForTenant(TenantResolver.effectiveHostId(tenantHeader, bankService));
    }
}
