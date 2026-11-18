package de.ostms.evils.ebics.camt;

import de.ostms.evils.ebics.domain.*;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/** One ISO document per original message. Several files must be transported in a container. */
@Component
public class Pain002Generator {
    public Map<String,String> generateFiles(List<PaymentOrder> orders){
        var groups=orders.stream().collect(Collectors.groupingBy(o->o.getSubmissionId()==null?"GUI-"+o.getId():o.getSubmissionId(),LinkedHashMap::new,Collectors.toList()));
        Map<String,String> files=new LinkedHashMap<>();
        int n=0;for(var group:groups.values())files.put("status-"+(++n)+".xml",generate(group));
        return files;
    }
    public String generate(List<PaymentOrder> orders){
        if(orders.isEmpty())throw new IllegalArgumentException("No payment status data");
        var first=orders.getFirst();
        if(orders.stream().anyMatch(o->!Objects.equals(first.getOriginalMessageId(),o.getOriginalMessageId())||!Objects.equals(first.getSubmissionId(),o.getSubmissionId())))throw new IllegalArgumentException("One original message per pain.002 document");
        StringBuilder xml=new StringBuilder(header("PSR"));
        xml.append("<OrgnlGrpInfAndSts><OrgnlMsgId>").append(escape(first.getOriginalMessageId()==null?"EVILS-"+first.getId():first.getOriginalMessageId()))
            .append("</OrgnlMsgId><OrgnlMsgNmId>").append(escape(first.getOriginalMessageName()==null?"EVILS-GUI":first.getOriginalMessageName()))
            .append("</OrgnlMsgNmId><OrgnlNbOfTxs>").append(orders.size()).append("</OrgnlNbOfTxs><GrpSts>").append(status(orders)).append("</GrpSts></OrgnlGrpInfAndSts>");
        var groups=orders.stream().collect(Collectors.groupingBy(o->o.getOriginalPaymentInformationId()==null?"EVILS-"+o.getId():o.getOriginalPaymentInformationId(),LinkedHashMap::new,Collectors.toList()));
        for(var entry:groups.entrySet()){
            xml.append("<OrgnlPmtInfAndSts><OrgnlPmtInfId>").append(escape(entry.getKey())).append("</OrgnlPmtInfId><PmtInfSts>").append(status(entry.getValue())).append("</PmtInfSts>");
            for(var order:entry.getValue()){
                xml.append("<TxInfAndSts>");
                if(order.getOriginalInstructionId()!=null)xml.append("<OrgnlInstrId>").append(escape(order.getOriginalInstructionId())).append("</OrgnlInstrId>");
                xml.append("<OrgnlEndToEndId>").append(escape(order.getEndToEndId()==null?"NOTPROVIDED":order.getEndToEndId())).append("</OrgnlEndToEndId><TxSts>")
                    .append(status(List.of(order))).append("</TxSts></TxInfAndSts>");
            }
            xml.append("</OrgnlPmtInfAndSts>");
        }
        return xml.append("</CstmrPmtStsRpt></Document>").toString();
    }
    private static String status(List<PaymentOrder> orders){
        if(orders.stream().allMatch(o->o.getStatus()==PaymentOrderStatus.APPROVED))return "ACSC";
        if(orders.stream().allMatch(o->o.getStatus()==PaymentOrderStatus.SCHEDULED))return "ACSP";
        if(orders.stream().allMatch(o->o.getStatus()==PaymentOrderStatus.PENDING_SIGNATURES))return "PDNG";
        return "PART";
    }
    public static String header(String prefix){return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:pain.002.001.03\"><CstmrPmtStsRpt><GrpHdr><MsgId>"+prefix+"-"+UUID.randomUUID().toString().replace("-", "").substring(0,30)+"</MsgId><CreDtTm>"+Instant.now()+"</CreDtTm></GrpHdr>";}
    public static String escape(String s){return s==null?"":s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;");}
}
