package de.ostms.evils.ebics.admin;
import de.ostms.evils.ebics.service.BankService;
import de.ostms.evils.ebics.service.TestScenarioService;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/admin/api/test-scenarios")
public class TestScenarioController {
    private final TestScenarioService service;
    private final BankService bankService;
    public TestScenarioController(TestScenarioService service, BankService bankService){this.service=service;this.bankService=bankService;}
    public record Request(String partnerId,String scenario){}
    @PostMapping public TestScenarioService.Result create(@RequestBody Request request,
            @RequestHeader(value = "X-Tenant-Host-Id", required = false) String tenantHeader){
        return service.create(TenantResolver.effectiveHostId(tenantHeader, bankService), request.partnerId(),request.scenario());
    }
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
    public java.util.Map<String,String> invalid(IllegalArgumentException error){return java.util.Map.of("error",error.getMessage());}
}
