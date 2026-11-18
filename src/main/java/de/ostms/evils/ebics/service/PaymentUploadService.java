package de.ostms.evils.ebics.service;

import de.ostms.evils.ebics.client.*;
import de.ostms.evils.ebics.domain.*;
import de.ostms.evils.ebics.persistence.*;
import jakarta.persistence.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.w3c.dom.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;

/** Single-payment EBICS BTU/SCT upload for the EVILS test server. */
@Service
public class PaymentUploadService {
    @org.springframework.beans.factory.annotation.Autowired private PaymentHistoryService history;
    private final EntityManager em;
    private final KeyManagementService participantKeys;
    private final BankKeyService bankKeys;
    private final BankService bank;
    private final PaymentOrderService payments;
    private final PaymentAuthorizationService authorizations;
    private final ProtocolLogService protocolLogs;
    private final PaymentUploadSessionRepository sessions;
    /** Protocol of the request currently being handled on this thread ("H004" = EBICS 2.5, else
     * H005). A request is processed synchronously on one thread, so this avoids threading a
     * namespace parameter through every XML helper  (CCT uploads over H004). */
    private static final ThreadLocal<String> PROTOCOL=new ThreadLocal<>();
    private static String version(){return "H004".equals(PROTOCOL.get())?"H004":"H005";}
    private static String ns(){return "urn:org:ebics:"+version();}
    public PaymentUploadService(EntityManager em,KeyManagementService participantKeys,BankKeyService bankKeys,BankService bank,PaymentOrderService payments,PaymentAuthorizationService authorizations,ProtocolLogService protocolLogs,PaymentUploadSessionRepository sessions){this.em=em;this.participantKeys=participantKeys;this.bankKeys=bankKeys;this.bank=bank;this.payments=payments;this.authorizations=authorizations;this.protocolLogs=protocolLogs;this.sessions=sessions;}

    @Transactional(rollbackFor=Exception.class)
    public String receive(String xml)throws Exception {
        var doc=HpbCodec.parse(xml.getBytes(StandardCharsets.UTF_8));var root=doc.getDocumentElement();
        String protocol=root.getAttribute("Version");
        boolean supported="ebicsRequest".equals(root.getLocalName())
                &&(("H005".equals(protocol)&&"urn:org:ebics:H005".equals(root.getNamespaceURI()))
                ||("H004".equals(protocol)&&"urn:org:ebics:H004".equals(root.getNamespaceURI())));
        if(!supported)throw new SecurityException("Unsupported protocol");
        PROTOCOL.set(protocol);
        try{return receive(doc,root);}finally{PROTOCOL.remove();}
    }

    private String receive(Document doc,Element root)throws Exception {
        Element header=unique(root,"header");
        String phase=text(header,"TransactionPhase");
        if("Initialisation".equals(phase)) return initialise(doc,root,header);
        String transactionId=text(header,"TransactionID");
        PaymentUploadSession session=sessions.findById(transactionId).orElseThrow(()->new SecurityException("Unknown payment transaction"));
        if(Duration.between(session.getCreatedAt(),LocalDateTime.now()).compareTo(Duration.ofMinutes(15))>0){sessions.delete(session);throw new SecurityException("Expired payment transaction");}
        if(!"true".equals(header.getAttribute("authenticate")))throw new SecurityException("Unsigned payment fields");
        EbicsAuthentication.verify(doc,key(session.getUserId(),"AUTHENTICATION","X002"));
        if("Transfer".equals(phase)) return transfer(doc,root,session);
        if("Receipt".equals(phase)){sessions.delete(session);return phaseResponse("Receipt",transactionId,"000000","[EBICS_OK] OK","");}
        throw new SecurityException("Unsupported transaction phase");
    }

    private String initialise(Document doc,Element root,Element header)throws Exception {
        // "BTU" is the EBICS-3.0-native upload identifier; "FUL" is the legacy per-type
        // identifier some clients (e.g. ebics-java-client, still built against the classic
        // OrderType enum) send instead - both carry the same BTUOrderParams/Service/Scope/
        // MsgName shape, so both are accepted here (see matching dispatch fix in
        // EbicsController.receive()).
        // EBICS 2.5 (H004) has no AdminOrderType/Service model: the classic OrderType CCT *is*
        // the SEPA credit transfer (pain.001) upload.
        String classicOrderType=optionalText(header,"OrderType");
        boolean classicSct="H004".equals(version())&&"CCT".equals(classicOrderType);
        if(!classicSct){
            String adminOrderType=text(header,"AdminOrderType");
            if((!"BTU".equals(adminOrderType)&&!"FUL".equals(adminOrderType))||!isSctPain001(header))throw new SecurityException("Unsupported upload profile");
        }
        if(bank.findBank(text(header,"HostID")).isEmpty())throw new SecurityException("Wrong HostID");
        String user=text(header,"UserID"),partner=text(header,"PartnerID");
        // Serialize all submissions for this subscriber, including concurrent duplicate requests.
        Participant participant=em.find(Participant.class,user,LockModeType.PESSIMISTIC_WRITE);
        if(participant==null||!participant.isActive()||!partner.equals(participant.getPartnerId())||!"INITIALIZED".equals(participant.getAuthenticationKeyStatus())||!"INITIALIZED".equals(participant.getSignatureKeyStatus()))throw new SecurityException("Participant is not initialized");
        if(!participant.isSignatureKeyReleased())throw new SecurityException("Signature key requires bank approval");
        // Only a user who may pay from no account at all is turned away here; the exact class depends on
        // the debtor account (per-account override, else the account-wide default) and is checked per
        // payment in processTransfer once the pain.001 has been decrypted.
        if(authorizations.listForUser(user).stream().allMatch(a->"T".equals(a.getSignatureClass())))throw new SecurityException("Technical user may not submit payments");
        EbicsAuthentication.verify(doc,key(user,"AUTHENTICATION","X002"));
        if(Duration.between(Instant.parse(text(header,"Timestamp")),Instant.now()).abs().compareTo(Duration.ofMinutes(5))>0)throw new SecurityException("Expired request");
        Element body=unique(root,"body");
        var transfers=body.getElementsByTagNameNS(ns(),"DataTransfer");
        // The legacy EVILS test client sends the encrypted payload in the Initialisation request.
        // Banking4 uses the normal Initialisation -> Transfer -> Receipt sequence.
        if(transfers.getLength()==1 && transfers.item(0).getParentNode()==body && hasOrderData((Element)transfers.item(0)))
            return processTransfer(doc,(Element)transfers.item(0),partner,user,null);
        String encryptedTransactionKey=null;
        if(transfers.getLength()==1 && transfers.item(0).getParentNode()==body){
            Element initialTransfer=(Element)transfers.item(0);
            if(!"true".equals(unique(initialTransfer,"DataEncryptionInfo").getAttribute("authenticate")))throw new SecurityException("Unsigned payment fields");
            encryptedTransactionKey=HpbCodec.text(initialTransfer,"TransactionKey");
        }
        String transactionId=HexFormat.of().formatHex(randomBytes(16)).toUpperCase(Locale.ROOT);
        var session=new PaymentUploadSession(transactionId,partner,user,encryptedTransactionKey);
        session.setEbicsOrderId(allocateOrderId());
        session.setServiceParameters(optionalText(header,"Scope"),optionalText(header,"ServiceOption"));
        sessions.save(session);
        return phaseResponse("Initialisation",transactionId,"000000","[EBICS_OK] OK","",session.getEbicsOrderId());
    }

    private String transfer(Document doc,Element root,PaymentUploadSession session)throws Exception {
        Element body=unique(root,"body"),transfer=unique(body,"DataTransfer");
        return processTransfer(doc,transfer,session.getPartnerId(),session.getUserId(),session.getTransactionId(),session.getEncryptedTransactionKey());
    }

    private String processTransfer(Document doc,Element transfer,String partner,String user,String transactionId)throws Exception {
        return processTransfer(doc,transfer,partner,user,transactionId,null);
    }
    private String processTransfer(Document doc,Element transfer,String partner,String user,String transactionId,String encryptedTransactionKey)throws Exception {
        // Standard H005 puts authenticate="true" on DataEncryptionInfo, not on the
        // surrounding DataTransfer element. The X002 check verifies this node.
        var encryptionInfo=transfer.getElementsByTagNameNS(ns(),"DataEncryptionInfo");
        byte[] clear;
        if(encryptionInfo.getLength()==1){
            if(!"true".equals(((Element)encryptionInfo.item(0)).getAttribute("authenticate")))throw new SecurityException("Unsigned payment fields");
            clear=PaymentWire.decrypt(transfer,bankKeys.privateKey("ENCRYPTION"));
        }else{
            if(encryptionInfo.getLength()!=0||encryptedTransactionKey==null||encryptedTransactionKey.isBlank())throw new SecurityException("Missing transaction encryption information");
            clear=PaymentWire.decrypt(transfer,bankKeys.privateKey("ENCRYPTION"),encryptedTransactionKey);
        }
        var envelope=HpbCodec.parse(clear).getDocumentElement();
        byte[] pain=clear;
        // EVILS-SCT-1 clients wrap the pain document in an A006 envelope. Standard BTU/SCT
        // clients send the encrypted pain.001 order data directly; their authenticated EBICS
        // request is already verified above.
        if("PaymentEnvelope".equals(envelope.getNodeName())){
            pain=Base64.getDecoder().decode(HpbCodec.text(envelope,"Pain001"));
            byte[] signature=Base64.getDecoder().decode(HpbCodec.text(envelope,"A006"));
            if(!A006Signature.verify(pain,signature,key(user,"SIGNATURE","A006")))throw new SecurityException("Invalid A006 payment signature");
        }
        // Lock subscriber in Transfer too: serializes retries and overlapping batches.
        Participant participant=em.find(Participant.class,user,LockModeType.PESSIMISTIC_WRITE);
        if(participant==null||!participant.isActive()||!partner.equals(participant.getPartnerId())||!participant.isSignatureKeyReleased())throw new SecurityException("Participant is not initialized");
        var parsed=PaymentWire.parseBatch(pain);
        for(var item:parsed.items()){
            Account account=em.find(Account.class,item.payment().debtorIban(),LockModeType.PESSIMISTIC_WRITE);
            if(account==null||!account.isActive()||!"EUR".equals(account.getCurrency())||!partner.equals(account.getPartnerId()))throw new SecurityException("Account is not owned by participant or not EUR");
            if("T".equals(authorizations.forAccount(user,item.payment().debtorIban()).getSignatureClass()))throw new SecurityException("Technical user may not submit payments for this account");
        }
        // Hash actual bytes: MsgId reuse with altered details cannot produce a second booking.
        String id=hash((partner+"\n"+parsed.messageId()).getBytes(StandardCharsets.UTF_8)),digest=hash(pain);
        String replyDigest="PaymentEnvelope".equals(envelope.getNodeName())?digest:"";
        if(transactionId!=null){var session=sessions.findById(transactionId).orElseThrow();
            if(session.getAcceptedDigest()!=null&&!session.getAcceptedDigest().equals(digest))throw new SecurityException("Transaction already contains a different payment file");
            session.setAcceptedDigest(digest);
        }
        PaymentSubmission previous=em.find(PaymentSubmission.class,id);
        if(previous!=null){if(!previous.digest.equals(digest))throw new SecurityException("Message ID already used for different payment");protocolLogs.uploadAccepted(participant.getHostId(),partner,user,String.valueOf(previous.orderId));return phaseResponse(transactionId==null?"Initialisation":"Transfer",transactionId,"000000","Bereits gebucht/angenommen: Auftrag "+previous.orderId,replyDigest,em.find(PaymentOrder.class,previous.orderId).getEbicsOrderId());}
        String wireOrderId=transactionId==null?allocateOrderId():sessions.findById(transactionId).orElseThrow().getEbicsOrderId();
        if(wireOrderId==null)wireOrderId=allocateOrderId();
        List<PaymentOrder> created=new ArrayList<>();
        for(var item:parsed.items()){
            var payment=item.payment();
            PaymentOrder order=payments.createFromEbics(payment.debtorIban(),payment.amount(),payment.creditorName(),payment.creditorIban(),null,payment.purpose(),payment.endToEndId(),payment.executionDate(),parsed.messageId());
            order.setUploadReferences(parsed.messageName(),item.paymentInformationId(),item.instructionId(),wireOrderId,id,user);
            var uploadSession=transactionId==null?null:sessions.findById(transactionId).orElseThrow();
            order.setServiceParameters(uploadSession==null?optionalText(doc.getDocumentElement(),"Scope"):uploadSession.getServiceScope(),uploadSession==null?optionalText(doc.getDocumentElement(),"ServiceOption"):uploadSession.getServiceOption());
            created.add(payments.sign(order.getId(),user));
        }
        em.persist(new PaymentSubmission(id,digest,created.getFirst().getId()));em.flush();
        history.uploaded(created);
        protocolLogs.uploadAccepted(participant.getHostId(),partner,user,wireOrderId);
        String status=created.stream().allMatch(o->o.getStatus()==PaymentOrderStatus.APPROVED)?"gebucht":"angenommen (Freigabe oder Termin ausstehend)";
        return phaseResponse(transactionId==null?"Initialisation":"Transfer",transactionId,"000000","Zahlungen im Testserver "+status+": "+created.size(),replyDigest,wireOrderId);
    }
    private String allocateOrderId(){var number=new EbicsOrderNumber();em.persist(number);em.flush();return number.value();}
    public String response(String code,String report)throws Exception {
        return response(code,report,"");
    }
    /** Same, but answered in the protocol of the failed request ("H004" or "H005"). */
    public String responseFor(String protocol,String code,String report)throws Exception {
        PROTOCOL.set(protocol);
        try{return response(code,report,"");}finally{PROTOCOL.remove();}
    }
    private String response(String code,String report,String digest)throws Exception {
        return phaseResponse("Initialisation",null,code,report,digest);
    }
    private String phaseResponse(String phase,String transactionId,String code,String report,String digest)throws Exception {
        return phaseResponse(phase,transactionId,code,report,digest,null);
    }
    private String phaseResponse(String phase,String transactionId,String code,String report,String digest,String orderId)throws Exception {
        String safe=report.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");
        String stat=(transactionId==null?"":"<TransactionID>"+transactionId+"</TransactionID>")+(digest.isEmpty()?"":"<RequestDigest>"+digest+"</RequestDigest>");
        String order=orderId==null?"":"<OrderID>"+orderId+"</OrderID>";
        return EbicsAuthentication.sign("<ebicsResponse xmlns=\""+ns()+"\" Version=\""+version()+"\"><header authenticate=\"true\"><static>"+stat+"</static><mutable><TransactionPhase>"+phase+"</TransactionPhase>"+order+"<ReturnCode>"+code+"</ReturnCode><ReportText>"+safe+"</ReportText></mutable></header><AuthSignature/><body><ReturnCode authenticate=\"true\">"+code+"</ReturnCode></body></ebicsResponse>",bankKeys.authenticationPrivateKey());
    }
    private PublicKey key(String user,String type,String version)throws Exception {
        var stored=participantKeys.keysOf(user).stream().filter(k->type.equals(k.getKeyType())).findFirst().orElseThrow();
        if(!version.equals(stored.getVersion()))throw new SecurityException("Unsupported key version");
        return HpbCodec.decodeKey(stored.getPublicKeyBase64());
    }
    private static String hash(byte[] value)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}
    public boolean isPaymentTransaction(String transactionId){return transactionId!=null&&sessions.existsById(transactionId);}
    private static boolean hasOrderData(Element transfer){return transfer.getElementsByTagNameNS(ns(),"OrderData").getLength()==1;}
    private static byte[] randomBytes(int length){byte[] value=new byte[length];new SecureRandom().nextBytes(value);return value;}
    private static boolean isSctPain001(Element header){
        String profile=optionalText(header,"Profile");
        if(PaymentWire.PROFILE.equals(profile))return true;
        String service=optionalText(header,"ServiceName"), message=optionalText(header,"MsgName");
        return "SCT".equalsIgnoreCase(service)&&message!=null&&message.toLowerCase(Locale.ROOT).startsWith("pain.001");
    }
    private static Element unique(Element e,String name){var nodes=e.getElementsByTagNameNS(ns(),name);if(nodes.getLength()!=1)throw new SecurityException("Missing or duplicate "+name);return (Element)nodes.item(0);}
    private static String text(Element e,String name){return unique(e,name).getTextContent().trim();}
    private static String optionalText(Element e,String name){var nodes=e.getElementsByTagNameNS(ns(),name);if(nodes.getLength()>1)throw new SecurityException("Duplicate "+name);return nodes.getLength()==0?null:nodes.item(0).getTextContent().trim();}
}
