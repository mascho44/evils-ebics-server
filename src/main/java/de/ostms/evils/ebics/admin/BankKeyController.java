package de.ostms.evils.ebics.admin;

import de.ostms.evils.ebics.domain.BankKey;
import de.ostms.evils.ebics.service.BankKeyService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/admin/api/crypto")
public class BankKeyController {
    private final BankKeyService service;
    public BankKeyController(BankKeyService service) { this.service = service; }
    @GetMapping("/status") public CryptoStatus status() { return new CryptoStatus(service.isConfigured(), service.all()); }
    @PostMapping("/bank-keys") @ResponseStatus(HttpStatus.CREATED) public List<BankKey> generate() { return service.generateActiveKeySet(); }
    public record CryptoStatus(boolean configured, List<BankKey> keys) { }
}
