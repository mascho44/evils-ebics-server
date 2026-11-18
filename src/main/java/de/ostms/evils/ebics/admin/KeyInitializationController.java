package de.ostms.evils.ebics.admin;

import de.ostms.evils.ebics.domain.KeyInitialization;
import de.ostms.evils.ebics.service.KeyManagementService;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/admin/api/key-initializations")
public class KeyInitializationController {
    private final KeyManagementService service;
    public KeyInitializationController(KeyManagementService service) { this.service = service; }
    @GetMapping public List<KeyInitialization> all() { return service.all(); }
}
