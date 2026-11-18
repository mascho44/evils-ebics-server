package de.ostms.evils.ebics.admin;
import de.ostms.evils.ebics.service.VeuService;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.util.Map;

@RestController
@RequestMapping("/admin/api/veu")
public class VeuController {
    private final VeuService service;
    public VeuController(VeuService service){this.service=service;}
    public record PublishRequest(String originator){}
    @PostMapping("/{paymentId}")
    public ResponseEntity<?> publish(@PathVariable Long paymentId,@RequestBody PublishRequest request){
        try {var v=service.publish(paymentId,request.originator());return ResponseEntity.ok(Map.of("paymentId",v.getPaymentId(),"orderId",v.getOrderId(),"message","Für VEU bereitgestellt; noch nicht unterschrieben oder gebucht."));}
        catch(IllegalArgumentException|java.util.NoSuchElementException e){return ResponseEntity.badRequest().body(Map.of("message",e.getMessage()==null?"Auftrag oder Teilnehmer nicht gefunden":e.getMessage()));}
    }
}
