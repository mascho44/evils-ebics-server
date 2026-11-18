package de.ostms.evils.ebics.admin;
import de.ostms.evils.ebics.domain.HacAcknowledgement;
import de.ostms.evils.ebics.service.HacService;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController @RequestMapping("/admin/api/hac-acknowledgements")
public class HacController {
    private final HacService service;
    public HacController(HacService service) { this.service=service; }
    @GetMapping public List<HacAcknowledgement> all() { return service.all(); }
}
