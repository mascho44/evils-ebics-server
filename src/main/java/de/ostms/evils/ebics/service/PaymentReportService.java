package de.ostms.evils.ebics.service;

import de.ostms.evils.ebics.camt.Pain002Generator;
import de.ostms.evils.ebics.domain.*;
import de.ostms.evils.ebics.persistence.*;
import de.ostms.evils.ebics.protocol.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.zip.*;
import static de.ostms.evils.ebics.camt.Pain002Generator.escape;

/**
 * Creates the payment status reports (pain.002) for payment orders and records every download so it
 * only counts as received after the client confirms it with a validly signed positive EBICS receipt.
 */
@Service
public class PaymentReportService {
    private final PaymentOrderRepository orders;
    private final PaymentEventRepository events;
    private final ReportDownloadRepository downloads;
    private final AccountRepository accounts;
    private final KeyManagementService keys;
    private final BankKeyService bankKeys;
    private final BankService bank;
    private final PaymentHistoryService history;
    private final StatusSchemaValidator validator;
    private final Pain002Generator generator;
    public PaymentReportService(PaymentOrderRepository orders,PaymentEventRepository events,ReportDownloadRepository downloads,AccountRepository accounts,KeyManagementService keys,BankKeyService bankKeys,BankService bank,PaymentHistoryService history,StatusSchemaValidator validator,Pain002Generator generator){this.orders=orders;this.events=events;this.downloads=downloads;this.accounts=accounts;this.keys=keys;this.bankKeys=bankKeys;this.bank=bank;this.history=history;this.validator=validator;this.generator=generator;}
    public boolean owns(String transactionId){return transactionId!=null&&downloads.existsById(transactionId);}
    private void authenticate(EbicsRequest request,String user)throws Exception{
        var participant=bank.findParticipant(user).orElseThrow(()->new SecurityException("Unknown participant"));
        if(!participant.isActive()||!participant.isSignatureKeyReleased())throw new SecurityException("Inactive participant");
        if(request.partnerId()!=null&&!participant.getPartnerId().equals(request.partnerId()))throw new SecurityException("Partner mismatch");
        var key=keys.keysOf(user).stream().filter(k->"AUTHENTICATION".equals(k.getKeyType())&&"X002".equals(k.getVersion())).findFirst().orElseThrow();
        EbicsAuthentication.verify(HpbCodec.parse(request.xml().getBytes(StandardCharsets.UTF_8)),HpbCodec.decodeKey(key.getPublicKeyBase64()));
    }
    @Transactional public String download(EbicsRequest request,boolean hac){
        try{
            authenticate(request,request.userId());
            LocalDate to=request.dateTo()==null?LocalDate.now():request.dateTo(),from=request.dateFrom()==null?to.minusDays(90):request.dateFrom();
            if(from.isAfter(to))throw new IllegalArgumentException("Invalid date range");
            Set<String> ibans=new HashSet<>();accounts.findAll().stream().filter(a->request.partnerId().equals(a.getPartnerId())).forEach(a->ibans.add(a.getIban()));
            var owned=orders.findAllByOrderByCreatedAtDesc().stream().filter(o->ibans.contains(o.getIban())).toList();
            List<PaymentOrder> included=new ArrayList<>();Set<String> eventIds=new HashSet<>();Map<String,String> files;
            if(hac){
                Map<Long,PaymentOrder> byId=new HashMap<>();owned.forEach(o->byId.put(o.getId(),o));
                Set<String> delivered=new HashSet<>();
                if(request.dateFrom()==null&&request.dateTo()==null)downloads.findByKindAndReceiptCode("HAC",0).forEach(d->delivered.addAll(d.getEventIds()));
                var selected=events.findAllByOrderByOccurredAtAsc().stream().filter(e->Set.of("FILE_UPLOAD","VEU_FORWARDING","ORDER_HAC_FINAL_POS").contains(e.getKind()))
                    .filter(e->byId.containsKey(e.getPaymentOrderId())&&!delivered.contains(e.getId()))
                    .filter(e->{var day=e.getOccurredAt().atZone(ZoneId.systemDefault()).toLocalDate();return !day.isBefore(from)&&!day.isAfter(to);}).toList();
                if(selected.isEmpty())return signed(new EbicsResponseBuilder().downloadNoDataAvailable(request));
                StringBuilder xml=new StringBuilder(Pain002Generator.header("HAC").replace("</GrpHdr>","<InitgPty><Id><OrgId><Othr><Id>"+escape(request.hostId())+"</Id></Othr></OrgId></Id></InitgPty></GrpHdr>"));
                xml.append("<OrgnlGrpInfAndSts><OrgnlMsgId>EBICS</OrgnlMsgId><OrgnlMsgNmId>EBICS</OrgnlMsgNmId></OrgnlGrpInfAndSts>");
                for(var event:selected){
                    var order=byId.get(event.getPaymentOrderId());included.add(order);eventIds.add(event.getId());
                    xml.append("<OrgnlPmtInfAndSts><OrgnlPmtInfId>").append(event.getKind()).append("</OrgnlPmtInfId><StsRsnInf><Orgtr><Id><OrgId>")
                        .append(identifier("PartnerID",request.partnerId())).append(identifier("UserID",order.getSubmittedBy())).append(identifier("OrderID",order.getEbicsOrderId()))
                        .append(identifier("AdminOrderType","BTU")).append(identifier("ServiceName","SCT")).append(identifier("MsgName","pain.001"))
                        .append(identifier("Scope",order.getServiceScope())).append(identifier("ServiceOption",order.getServiceOption()))
                        .append(identifier("TimeStamp",event.getOccurredAt().toString())).append("</OrgId></Id></Orgtr>");
                    if(!event.getKind().equals("ORDER_HAC_FINAL_POS"))xml.append("<Rsn><Cd>").append(event.getKind().equals("FILE_UPLOAD")?"TS01":"DS06").append("</Cd></Rsn>");
                    xml.append("</StsRsnInf></OrgnlPmtInfAndSts>");
                }
                files=Map.of("hac.xml",xml.append("</CstmrPmtStsRpt></Document>").toString());
            }else{
                // Status selection follows submission date, not the future execution date.
                included=owned.stream().filter(o->o.getOriginalMessageName()!=null)
                    .filter(o->!o.getCreatedAt().toLocalDate().isBefore(from)&&!o.getCreatedAt().toLocalDate().isAfter(to)).toList();
                if(included.isEmpty())return signed(new EbicsResponseBuilder().downloadNoDataAvailable(request));
                files=generator.generateFiles(included);
            }
            files.values().forEach(validator::validate);
            String tx=UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
            byte[] payload=files.size()==1&&!request.xml().contains("ZIP")?files.values().iterator().next().getBytes(StandardCharsets.UTF_8):zip(files);
            String response=signed(new EbicsResponseBuilder().downloadInitialization(request,payload,keys.activeEncryptionKey(request.userId()),tx));
            var ids=included.stream().map(PaymentOrder::getId).distinct().toList();
            downloads.save(new ReportDownload(tx,request.userId(),hac?"HAC":"PSR",ids,eventIds));
            for(Long id:ids)history.record(id,"REPORT_PROVIDED",(hac?"HAC":"pain.002")+" bereitgestellt; noch keine Empfangsbestätigung");
            return response;
        }catch(Exception e){throw new EbicsProtocolException("Status download rejected: "+e.getMessage(),e);}
    }
    @Transactional public String receipt(EbicsRequest request){
        try{
            var download=downloads.findLocked(request.transactionId()).orElseThrow();
            authenticate(request,download.getUserId());
            if(download.getCreatedAt().plusSeconds(3600).isBefore(Instant.now()))throw new SecurityException("Expired report download");
            var root=HpbCodec.parse(request.xml().getBytes(StandardCharsets.UTF_8)).getDocumentElement();
            var codes=root.getElementsByTagNameNS(root.getNamespaceURI(),"ReceiptCode");
            if(codes.getLength()!=1)throw new SecurityException("Missing receipt code");
            int code=Integer.parseInt(codes.item(0).getTextContent().trim());
            if(code!=0&&code!=1)throw new SecurityException("Invalid receipt code");
            if(download.getReceiptCode()!=null&&download.getReceiptCode()!=code)throw new SecurityException("Conflicting receipt");
            if(download.getReceiptCode()==null){
                download.receipt(code);
                for(Long id:download.getOrderIds()){
                    if(code==0&&download.getKind().equals("PSR"))orders.findById(id).ifPresent(PaymentOrder::markStatusReportRetrieved);
                    history.record(id,code==0?"REPORT_RECEIVED":"REPORT_REJECTED",download.getKind()+": "+(code==0?"Empfang vom Client bestätigt (keine fachliche Importbestätigung)":"Client meldet fehlgeschlagenen Empfang"));
                }
            }
            return signed(new EbicsResponseBuilder().downloadReceipt(request.transactionId(),request));
        }catch(Exception e){throw new EbicsProtocolException("Status receipt rejected",e);}
    }
    private String signed(String xml)throws Exception{return EbicsAuthentication.sign(xml,bankKeys.authenticationPrivateKey());}
    private static String identifier(String name,String value){return value==null?"":"<Othr><Id>"+escape(value)+"</Id><SchmeNm><Prtry>"+name+"</Prtry></SchmeNm></Othr>";}
    private static byte[] zip(Map<String,String> files)throws Exception{var bytes=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(bytes)){for(var file:files.entrySet()){zip.putNextEntry(new ZipEntry(file.getKey()));zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));zip.closeEntry();}}return bytes.toByteArray();}
}
