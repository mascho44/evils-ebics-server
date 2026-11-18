package de.ostms.evils.ebics.service;

import de.ostms.evils.ebics.client.*;
import de.ostms.evils.ebics.domain.*;
import de.ostms.evils.ebics.persistence.*;
import de.ostms.evils.ebics.protocol.*;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.w3c.dom.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import static de.ostms.evils.ebics.camt.Pain002Generator.escape;

/** Single-payment H005 EDS workflow. Never treats X002 transport authentication as an A006 signature. */
@Service
public class VeuService {
    private static final String NS="urn:org:ebics:H005", SIG="http://www.ebics.org/S002";
    private static final String SERVICE="<Service><ServiceName>SCT</ServiceName><Scope>DE</Scope><MsgName version=\"09\" variant=\"001\" format=\"XML\">pain.001</MsgName></Service>";
    private final VeuOrderRepository veu;
    private final PaymentOrderRepository orders;
    private final AccountRepository accounts;
    private final ParticipantRepository participants;
    private final PaymentSignatureRepository signatures;
    private final PaymentOrderService payments;
    private final PaymentAuthorizationService permissions;
    private final PaymentHistoryService history;
    private final KeyManagementService keys;
    private final BankKeyService bankKeys;
    private final BankService bank;
    private final ReportDownloadRepository downloads;
    private final EntityManager em;
    public VeuService(VeuOrderRepository veu,PaymentOrderRepository orders,AccountRepository accounts,ParticipantRepository participants,PaymentSignatureRepository signatures,PaymentOrderService payments,PaymentAuthorizationService permissions,PaymentHistoryService history,KeyManagementService keys,BankKeyService bankKeys,BankService bank,ReportDownloadRepository downloads,EntityManager em){
        this.veu=veu;this.orders=orders;this.accounts=accounts;this.participants=participants;this.signatures=signatures;this.payments=payments;this.permissions=permissions;this.history=history;this.keys=keys;this.bankKeys=bankKeys;this.bank=bank;this.downloads=downloads;this.em=em;
    }
    @Transactional
    public VeuOrder publish(Long id,String originator){
        var order=orders.findLockedById(id).orElseThrow();
        var existing=veu.findById(id);if(existing.isPresent())return existing.get();
        if(order.getStatus()!=PaymentOrderStatus.PENDING_SIGNATURES||!payments.signatures(id).isEmpty()||order.getSubmissionId()!=null)
            throw new IllegalArgumentException("Only unsigned GUI drafts can be published to VEU");
        var account=accounts.findById(order.getIban()).orElseThrow();
        var user=participants.findById(originator).orElseThrow();
        if(!account.isActive()||!"EUR".equals(account.getCurrency())||!account.getPartnerId().equals(user.getPartnerId())||!user.isActive())
            throw new IllegalArgumentException("Active EUR account and matching participant required");
        var payment=new SepaPayment(account.getAccountHolder(),order.getIban(),order.getBeneficiaryName(),order.getBeneficiaryIban(),order.getAmount(),order.getPurpose(),order.getExecutionDate(),order.getEndToEndId());
        var number=new EbicsOrderNumber();em.persist(number);em.flush();String wireId=number.value();
        String messageId="VEU-"+wireId+"-"+id;
        String xml=payment.toXml(messageId,OffsetDateTime.now(ZoneOffset.UTC));
        var result=veu.save(new VeuOrder(id,wireId,account.getPartnerId(),originator,xml));
        order.setOriginalMessageId(messageId);
        order.setUploadReferences("pain.001.001.09",messageId,null,wireId,"VEU-"+id,originator);
        order.setServiceParameters("DE",null);
        history.record(id,"VEU_PUBLISHED","Unsignierter Auftrag für EBICS-VEU bereitgestellt: "+wireId);
        history.record(id,"VEU_FORWARDING","Auftrag wartet auf elektronische Unterschrift über VEU");
        return result;
    }
    @Transactional(rollbackFor=Exception.class)
    public String receive(EbicsRequest request)throws Exception {
        Document doc=HpbCodec.parse(request.xml().getBytes(StandardCharsets.UTF_8));
        Element root=doc.getDocumentElement(),header=child(root,"header"),stat=child(header,"static");
        if(!"ebicsRequest".equals(root.getLocalName())||!NS.equals(root.getNamespaceURI())||!"H005".equals(root.getAttribute("Version")))throw new SecurityException("Unsupported VEU envelope");
        if(!"true".equals(header.getAttribute("authenticate"))||!"Initialisation".equals(request.transactionPhase())||bank.findBank(request.hostId()).isEmpty())throw new SecurityException("Invalid VEU header");
        var user=participants.findById(request.userId()).orElseThrow();
        if(!user.isActive()||!user.isSignatureKeyReleased()||!user.getPartnerId().equals(request.partnerId())||"T".equals(permissions.forUser(user.getUserId()).getSignatureClass()))throw new SecurityException("VEU permission denied");
        EbicsAuthentication.verify(doc,key(user.getUserId(),"AUTHENTICATION","X002"));
        String timestamp=text(stat,"Timestamp");
        if(Duration.between(Instant.parse(timestamp),Instant.now()).abs().compareTo(Duration.ofMinutes(5))>0)throw new SecurityException("Expired VEU request");
        String type=request.orderType();Element details=child(stat,"OrderDetails");
        if(Set.of("HVZ","HVU").contains(type)){
            var pending=veu.findByPartnerIdOrderByCreatedAtAsc(user.getPartnerId()).stream().filter(v->visible(v,user.getUserId())).filter(v->matchesFilter(details,type)).toList();
            if(pending.isEmpty())return signed(new EbicsResponseBuilder().downloadNoDataAvailable());
            StringBuilder xml=new StringBuilder("<"+type+"ResponseOrderData xmlns=\""+NS+"\">");
            for(var v:pending)xml.append(summary(v,user.getUserId(),type.equals("HVZ")));
            xml.append("</").append(type).append("ResponseOrderData>");
            return download(request,xml.toString().getBytes(StandardCharsets.UTF_8),pending.stream().map(VeuOrder::getPaymentId).toList());
        }
        Element params=child(details,type+"OrderParams");
        var v=veu.findByOrderId(text(params,"OrderID")).orElseThrow(()->new SecurityException("Unknown VEU order"));
        if(!v.getPartnerId().equals(user.getPartnerId())||!v.getPartnerId().equals(text(params,"PartnerID"))||!matchesService(child(params,"Service")))throw new SecurityException("VEU order ownership or service mismatch");
        var order=orders.findLockedById(v.getPaymentId()).orElseThrow();
        var account=accounts.findById(order.getIban()).orElseThrow();
        if(!account.isActive()||!account.getPartnerId().equals(v.getPartnerId()))throw new SecurityException("Inactive account or ownership changed");
        if(type.equals("HVE"))return signOrder(request,root,v,order);
        if(order.getStatus()!=PaymentOrderStatus.PENDING_SIGNATURES)throw new SecurityException("VEU order no longer pending");
        if(type.equals("HVD"))return download(request,("<HVDResponseOrderData xmlns=\""+NS+"\">"+digest(v)+"<DisplayFile>"+Base64.getEncoder().encodeToString((order.getAmount()+" EUR; "+order.getBeneficiaryName()+"; "+order.getPurpose()).getBytes(StandardCharsets.UTF_8))+"</DisplayFile>"+availability(v)+signers(v)+"</HVDResponseOrderData>").getBytes(StandardCharsets.UTF_8),List.of(order.getId()));
        if(type.equals("HVT")){
            Element flags=child(params,"OrderFlags");
            if("true".equals(flags.getAttribute("completeOrderData"))||"1".equals(flags.getAttribute("completeOrderData")))return download(request,bytes(v),List.of(order.getId()));
            int offset=Integer.parseInt(flags.getAttribute("fetchOffset"));
            int limit=Integer.parseInt(flags.getAttribute("fetchLimit"));
            if(offset!=0||limit<0)throw new IllegalArgumentException("Single-payment VEU requires offset zero");
            String info="<HVTResponseOrderData xmlns=\""+NS+"\"><NumOrderInfos>1</NumOrderInfos><OrderInfo><MsgName version=\"09\" variant=\"001\" format=\"XML\">pain.001</MsgName>"+accountInfo(order.getIban(),"Originator")+accountInfo(order.getBeneficiaryIban(),"Recipient")+"<ExecutionDate>"+order.getExecutionDate()+"</ExecutionDate><Amount isCredit=\"false\" Currency=\"EUR\">"+order.getAmount()+"</Amount><Description Type=\"Purpose\">"+escape(order.getPurpose())+"</Description></OrderInfo></HVTResponseOrderData>";
            return download(request,info.getBytes(StandardCharsets.UTF_8),List.of(order.getId()));
        }
        throw new IllegalArgumentException("Unsupported VEU operation");
    }
    private String signOrder(EbicsRequest request,Element root,VeuOrder v,PaymentOrder order)throws Exception{
        Element transfer=child(child(root,"body"),"DataTransfer"),encryption=child(transfer,"DataEncryptionInfo"),signature=child(transfer,"SignatureData");
        if(!"true".equals(encryption.getAttribute("authenticate"))||!"true".equals(signature.getAttribute("authenticate")))throw new SecurityException("Unsigned VEU signature transport");
        Element suppliedDigest=child(transfer,"DataDigest");
        if(!"A006".equals(suppliedDigest.getAttribute("SignatureVersion"))||!MessageDigest.isEqual(A006Signature.digest(bytes(v)),Base64.getMimeDecoder().decode(suppliedDigest.getTextContent())))throw new SecurityException("VEU order digest mismatch");
        // SignatureData uses the same E002 transaction-key envelope as OrderData.
        var copy=(Element)transfer.cloneNode(true);
        var copied=child(copy,"SignatureData");copy.getOwnerDocument().renameNode(copied,NS,"OrderData");
        byte[] clear=PaymentWire.decrypt(copy,bankKeys.privateKey("ENCRYPTION"));
        var sigRoot=HpbCodec.parse(clear).getDocumentElement();
        if(!SIG.equals(sigRoot.getNamespaceURI())||!"UserSignatureData".equals(sigRoot.getLocalName()))throw new SecurityException("Invalid signature document");
        var list=sigRoot.getElementsByTagNameNS(SIG,"OrderSignatureData");
        if(list.getLength()!=1)throw new SecurityException("Exactly one VEU signature required");
        var sig=(Element)list.item(0);
        if(!request.userId().equals(sigText(sig,"UserID"))||!request.partnerId().equals(sigText(sig,"PartnerID"))||!"A006".equals(sigText(sig,"SignatureVersion")))throw new SecurityException("Signature identity/version mismatch");
        byte[] value=Base64.getMimeDecoder().decode(sigText(sig,"SignatureValue"));
        if(!A006Signature.verify(bytes(v),value,key(request.userId(),"SIGNATURE","A006")))throw new SecurityException("Invalid A006 VEU signature");
        // Payment lock serializes duplicates and signatures by different users.
        if(!signatures.existsByPaymentOrderIdAndUserId(order.getId(),request.userId())){
            if(order.getStatus()!=PaymentOrderStatus.PENDING_SIGNATURES)throw new SecurityException("VEU order no longer pending");
            payments.sign(order.getId(),request.userId());
            history.record(order.getId(),"VEU_SIGNED","A006-Unterschrift kryptografisch geprüft: "+request.userId());
            if(order.getStatus()!=PaymentOrderStatus.PENDING_SIGNATURES)history.record(order.getId(),"ORDER_HAC_FINAL_POS","VEU-Auftrag freigegeben: "+v.getOrderId());
        }
        String tx=transactionId();downloads.save(new ReportDownload(tx,request.userId(),"VEU",List.of(order.getId()),Set.of()));
        return signed("<ebicsResponse xmlns=\""+NS+"\" Version=\"H005\"><header authenticate=\"true\"><static><TransactionID>"+tx+"</TransactionID></static><mutable><TransactionPhase>Initialisation</TransactionPhase><OrderID>"+v.getOrderId()+"</OrderID><ReturnCode>000000</ReturnCode><ReportText>[EBICS_OK] OK</ReportText></mutable></header><AuthSignature/><body><ReturnCode authenticate=\"true\">000000</ReturnCode></body></ebicsResponse>");
    }
    private boolean visible(VeuOrder v,String user){var o=orders.findById(v.getPaymentId()).orElseThrow();var account=accounts.findById(o.getIban());return o.getStatus()==PaymentOrderStatus.PENDING_SIGNATURES&&account.isPresent()&&account.get().isActive()&&account.get().getPartnerId().equals(v.getPartnerId())&&!signatures.existsByPaymentOrderIdAndUserId(o.getId(),user);}
    private boolean matchesFilter(Element details,String type){Element params=optionalChild(details,type+"OrderParams");if(params==null)return true;var filters=params.getElementsByTagNameNS(NS,"ServiceFilter");if(filters.getLength()==0)return true;for(int i=0;i<filters.getLength();i++)if(matchesService((Element)filters.item(i)))return true;return false;}
    private boolean matchesService(Element service){for(String name:List.of("ServiceName","Scope","ServiceOption","MsgName","Container")){var field=optionalChild(service,name);if(field==null)continue;String value=field.getTextContent().trim();if(name.equals("ServiceName")&&!value.equals("SCT"))return false;if(name.equals("Scope")&&!value.equals("DE"))return false;if(name.equals("ServiceOption")||name.equals("Container"))return false;if(name.equals("MsgName")){if(!value.equals("pain.001"))return false;if(field.hasAttribute("version")&&!field.getAttribute("version").equals("09"))return false;if(field.hasAttribute("variant")&&!field.getAttribute("variant").equals("001"))return false;}}return true;}
    private String summary(VeuOrder v,String user,boolean extended)throws Exception{
        var o=orders.findById(v.getPaymentId()).orElseThrow();int done=payments.signatures(o.getId()).size();int needed="E".equals(permissions.forAccount(user,o.getIban()).getSignatureClass())?1:2;
        return "<OrderDetails>"+SERVICE+"<OrderID>"+v.getOrderId()+"</OrderID>"+(extended?digest(v)+availability(v)+"<TotalOrders>1</TotalOrders><TotalAmount isCredit=\"false\">"+o.getAmount()+"</TotalAmount><Currency>EUR</Currency>":"<OrderDataSize>"+bytes(v).length+"</OrderDataSize>")+"<SigningInfo readyToBeSigned=\"true\" NumSigRequired=\""+needed+"\" NumSigDone=\""+done+"\"/>"+signers(v)+"<OriginatorInfo><PartnerID>"+escape(v.getPartnerId())+"</PartnerID><UserID>"+escape(v.getOriginator())+"</UserID><Timestamp>"+v.getCreatedAt()+"</Timestamp></OriginatorInfo><AdditionalOrderInfo>"+escape(o.getPurpose())+"</AdditionalOrderInfo></OrderDetails>";
    }
    private String signers(VeuOrder v){StringBuilder xml=new StringBuilder();for(var s:payments.signatures(v.getPaymentId()))xml.append("<SignerInfo><PartnerID>").append(escape(v.getPartnerId())).append("</PartnerID><UserID>").append(escape(s.getUserId())).append("</UserID><Timestamp>").append(s.getSignedAt().atOffset(ZoneOffset.UTC)).append("</Timestamp><Permission AuthorisationLevel=\"").append(s.getSignatureClass()).append("\"/></SignerInfo>");return xml.toString();}
    private String download(EbicsRequest request,byte[] xml,List<Long> ids)throws Exception{String tx=transactionId();downloads.save(new ReportDownload(tx,request.userId(),"VEU",ids,Set.of()));return signed(new EbicsResponseBuilder().downloadInitialization(request,xml,keys.activeEncryptionKey(request.userId()),tx));}
    private String signed(String xml)throws Exception{return EbicsAuthentication.sign(xml,bankKeys.authenticationPrivateKey());}
    private static String transactionId(){return UUID.randomUUID().toString().replace("-","").toUpperCase(Locale.ROOT);}
    private static byte[] bytes(VeuOrder v){return v.getPaymentXml().getBytes(StandardCharsets.UTF_8);}
    private static String availability(VeuOrder v){return "<OrderDataAvailable>true</OrderDataAvailable><OrderDataSize>"+bytes(v).length+"</OrderDataSize><OrderDetailsAvailable>true</OrderDetailsAvailable>";}
    private static String digest(VeuOrder v)throws Exception{return "<DataDigest SignatureVersion=\"A006\">"+Base64.getEncoder().encodeToString(A006Signature.digest(bytes(v)))+"</DataDigest>";}
    private static String accountInfo(String iban,String role){return "<AccountInfo><AccountNumber Role=\""+role+"\" international=\"true\">"+escape(iban)+"</AccountNumber></AccountInfo>";}
    private PublicKey key(String user,String type,String version)throws Exception{var k=keys.keysOf(user).stream().filter(x->type.equals(x.getKeyType())&&version.equals(x.getVersion())).findFirst().orElseThrow();return HpbCodec.decodeKey(k.getPublicKeyBase64());}
    private static Element optionalChild(Element parent,String name){Element result=null;for(Node n=parent.getFirstChild();n!=null;n=n.getNextSibling())if(n instanceof Element e&&NS.equals(e.getNamespaceURI())&&name.equals(e.getLocalName())){if(result!=null)throw new SecurityException("Duplicate "+name);result=e;}return result;}
    private static Element child(Element parent,String name){var e=optionalChild(parent,name);if(e==null)throw new SecurityException("Missing "+name);return e;}
    private static String text(Element parent,String name){return child(parent,name).getTextContent().trim();}
    private static String sigText(Element parent,String name){var list=parent.getElementsByTagNameNS(SIG,name);if(list.getLength()!=1)throw new SecurityException("Invalid "+name);return list.item(0).getTextContent().trim();}
}
