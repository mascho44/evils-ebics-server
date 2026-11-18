package de.ostms.evils.ebics.admin;

import de.ostms.evils.ebics.domain.*;
import de.ostms.evils.ebics.service.PaymentOrderService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/admin/api/payment-orders")
public class PaymentOrderController {
    private final PaymentOrderService service;
    public PaymentOrderController(PaymentOrderService service) { this.service=service; }
    @GetMapping public List<PaymentOrder> all() { return service.all(); }
    @GetMapping("/{id}") public PaymentOrderService.Detail detail(@PathVariable Long id) { return service.detail(id); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public PaymentOrder create(@RequestBody CreatePaymentOrderRequest request) { return service.create(request.iban(), request.amount(), request.beneficiaryName(), request.beneficiaryIban(), request.beneficiaryBic(), request.purpose(), request.endToEndId(), request.executionDate()); }
    @GetMapping("/{id}/signatures") public List<PaymentSignature> signatures(@PathVariable Long id) { return service.signatures(id); }
    @PostMapping("/{id}/signatures") public PaymentOrder sign(@PathVariable Long id, @RequestBody SignPaymentOrderRequest request) { return service.sign(id, request.userId()); }
}
