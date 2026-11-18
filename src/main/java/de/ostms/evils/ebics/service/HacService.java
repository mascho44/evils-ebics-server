package de.ostms.evils.ebics.service;
import de.ostms.evils.ebics.domain.HacAcknowledgement;
import de.ostms.evils.ebics.persistence.HacAcknowledgementRepository;
import de.ostms.evils.ebics.protocol.EbicsRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
@Service
public class HacService {
    private final HacAcknowledgementRepository acknowledgements;
    public HacService(HacAcknowledgementRepository acknowledgements) { this.acknowledgements=acknowledgements; }
    @Transactional public HacAcknowledgement acknowledge(EbicsRequest request) { return acknowledgements.save(new HacAcknowledgement(request.hostId(), request.partnerId(), request.userId(), request.orderId(), request.version())); }
    public List<HacAcknowledgement> all() { return acknowledgements.findAllByOrderByReceivedAtDesc(); }
}
