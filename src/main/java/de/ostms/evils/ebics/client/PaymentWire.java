package de.ostms.evils.ebics.client;

import de.ostms.evils.ebics.service.HpbCodec;
import org.w3c.dom.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import java.io.*;
import java.util.zip.InflaterInputStream;

/** Parser for the single-credit-transfer subset used by the EVILS test server. */
public final class PaymentWire {
    public static final String PROFILE="EVILS-SCT-1";
    public static final String NS="urn:iso:std:iso:20022:tech:xsd:pain.001.001.09";
    private PaymentWire() {}
    public static String wrap(byte[] xml,PrivateKey key) throws Exception {
        return "<PaymentEnvelope><Pain001>"+Base64.getEncoder().encodeToString(xml)+"</Pain001><A006>"+Base64.getEncoder().encodeToString(A006Signature.sign(xml,key))+"</A006></PaymentEnvelope>";
    }
    public static byte[] decrypt(Element root,PrivateKey key)throws Exception {
        return decrypt(root,key,HpbCodec.text(root,"TransactionKey"));
    }
    /** Decrypts a transfer segment whose transaction key was negotiated in an earlier phase. */
    public static byte[] decrypt(Element root,PrivateKey key,String encryptedTransactionKey)throws Exception {
        var rsa=Cipher.getInstance("RSA/ECB/PKCS1Padding");rsa.init(Cipher.DECRYPT_MODE,key);
        byte[] secret=rsa.doFinal(Base64.getDecoder().decode(encryptedTransactionKey));
        try {
            var aes=Cipher.getInstance("AES/CBC/NoPadding");aes.init(Cipher.DECRYPT_MODE,new SecretKeySpec(secret,"AES"),new IvParameterSpec(new byte[16]));
            byte[] data=aes.doFinal(Base64.getDecoder().decode(HpbCodec.text(root,"OrderData")));
            if(data.length==0)throw new GeneralSecurityException("Empty encrypted payment");
            int pad=data[data.length-1]&255;if(pad<1||pad>16||pad>data.length)throw new GeneralSecurityException("Invalid padding");
            try(var input=new InflaterInputStream(new ByteArrayInputStream(data,0,data.length-pad))){byte[] clear=input.readNBytes(131073);if(clear.length>131072)throw new IOException("Payment exceeds size limit");return clear;}
        }finally{Arrays.fill(secret,(byte)0);}
    }
    public record Parsed(String messageId,SepaPayment payment) {}
    public record Item(String paymentInformationId, String instructionId, SepaPayment payment) {}
    public record Batch(String messageId,String messageName,List<Item> items) {}
    /** Parse a complete file before booking anything. The enclosing service transaction is atomic. */
    public static Batch parseBatch(byte[] xml)throws Exception {
        var root=HpbCodec.parse(xml).getDocumentElement();
        if(!"Document".equals(root.getLocalName())||!isPainNamespace(root.getNamespaceURI()))throw new IllegalArgumentException("Expected pain.001 document");
        Element init=child(root,"CstmrCdtTrfInitn"),group=child(init,"GrpHdr");
        String messageId=reference(value(group,"MsgId"));
        List<Item> items=new ArrayList<>();
        java.math.BigDecimal total=java.math.BigDecimal.ZERO;
        Set<String> infoIds=new HashSet<>();
        for(Element info:children(init,"PmtInf")){
            String infoId=reference(value(info,"PmtInfId"));
            if(!infoIds.add(infoId))throw new IllegalArgumentException("Duplicate PmtInfId");
            if(!"TRF".equals(value(info,"PmtMtd")))throw new IllegalArgumentException("Only credit transfers supported");
            Element type=optionalChild(info,"PmtTpInf");
            if(type!=null&&optionalChild(type,"SvcLvl")!=null&&!"SEPA".equals(value(child(type,"SvcLvl"),"Cd")))throw new IllegalArgumentException("Only SEPA supported");
            Element requested=child(info,"ReqdExctnDt");
            String date=optionalChild(requested,"Dt")==null?requested.getTextContent().trim():value(requested,"Dt");
            List<Element> txs=children(info,"CdtTrfTxInf");
            java.math.BigDecimal subtotal=java.math.BigDecimal.ZERO;
            for(Element tx:txs){
                Element amountElement=child(child(tx,"Amt"),"InstdAmt");
                if(!"EUR".equals(amountElement.getAttribute("Ccy")))throw new IllegalArgumentException("Only EUR supported");
                var amount=new java.math.BigDecimal(amountElement.getTextContent().trim());
                Element paymentId=child(tx,"PmtId");
                String instruction=optionalChild(paymentId,"InstrId")==null?null:reference(value(paymentId,"InstrId"));
                String purpose=optionalChild(tx,"RmtInf")==null?"SEPA-Überweisung "+messageId:value(child(tx,"RmtInf"),"Ustrd");
                var payment=new SepaPayment(value(child(info,"Dbtr"),"Nm"),value(child(child(info,"DbtrAcct"),"Id"),"IBAN"),
                    value(child(tx,"Cdtr"),"Nm"),value(child(child(tx,"CdtrAcct"),"Id"),"IBAN"),amount,purpose,
                    java.time.LocalDate.parse(date),reference(value(paymentId,"EndToEndId")));
                items.add(new Item(infoId,instruction,payment));subtotal=subtotal.add(amount);
                if(items.size()>1000)throw new IllegalArgumentException("At most 1000 payments per upload");
            }
            if(txs.isEmpty())throw new IllegalArgumentException("Empty payment information");
            checkTotals(info,txs.size(),subtotal,false);total=total.add(subtotal);
        }
        if(items.isEmpty())throw new IllegalArgumentException("Empty payment file");
        checkTotals(group,items.size(),total,true);
        return new Batch(messageId,root.getNamespaceURI().substring("urn:iso:std:iso:20022:tech:xsd:".length()),List.copyOf(items));
    }
    private static void checkTotals(Element element,int count,java.math.BigDecimal sum,boolean required){
        Element number=optionalChild(element,"NbOfTxs"),control=optionalChild(element,"CtrlSum");
        if((required&&number==null)||(number!=null&&Integer.parseInt(number.getTextContent().trim())!=count))throw new IllegalArgumentException("Transaction count mismatch");
        if(control!=null&&sum.compareTo(new java.math.BigDecimal(control.getTextContent().trim()))!=0)throw new IllegalArgumentException("Control sum mismatch");
    }
    private static String reference(String value){if(value==null||value.isBlank()||value.length()>35||value.codePoints().anyMatch(c->c<32))throw new IllegalArgumentException("Invalid payment reference");return value;}
    public static Parsed parse(byte[] xml)throws Exception {
        var root=HpbCodec.parse(xml).getDocumentElement();
        if(!"Document".equals(root.getLocalName())||!isPainNamespace(root.getNamespaceURI()))throw new IllegalArgumentException("Expected pain.001 document");
        Element init=child(root,"CstmrCdtTrfInitn"),group=child(init,"GrpHdr"),info=onlyChild(init,"PmtInf"),tx=onlyChild(info,"CdtTrfTxInf");
        if(!"1".equals(value(group,"NbOfTxs"))||!"1".equals(value(info,"NbOfTxs")))throw new IllegalArgumentException("Exactly one payment required");
        if(!"TRF".equals(value(info,"PmtMtd"))||!"SEPA".equals(value(child(child(info,"PmtTpInf"),"SvcLvl"),"Cd")))throw new IllegalArgumentException("Only SEPA credit transfers supported");
        Element instructed=child(child(tx,"Amt"),"InstdAmt");if(!"EUR".equals(instructed.getAttribute("Ccy")))throw new IllegalArgumentException("Only EUR supported");
        var amount=new java.math.BigDecimal(instructed.getTextContent());
        if(amount.signum()<=0||amount.compareTo(new java.math.BigDecimal(value(group,"CtrlSum")))!=0||amount.compareTo(new java.math.BigDecimal(value(info,"CtrlSum")))!=0)throw new IllegalArgumentException("Control sum mismatch");
        String id=value(group,"MsgId");if(!id.matches("[A-Za-z0-9_-]{1,35}"))throw new IllegalArgumentException("Invalid message ID");
        String debtorName=value(child(info,"Dbtr"),"Nm");
        if(!debtorName.equals(value(child(group,"InitgPty"),"Nm")))throw new IllegalArgumentException("Unsupported payment metadata");
        Element requested=child(info,"ReqdExctnDt");
        String requestedDate=optionalChild(requested,"Dt") == null ? requested.getTextContent().trim() : optionalChild(requested,"Dt").getTextContent().trim();
        String purpose=optionalChild(tx,"RmtInf") == null ? "SEPA-Überweisung " + id : value(child(tx,"RmtInf"),"Ustrd");
        var payment=new SepaPayment(debtorName,value(child(child(info,"DbtrAcct"),"Id"),"IBAN"),value(child(tx,"Cdtr"),"Nm"),value(child(child(tx,"CdtrAcct"),"Id"),"IBAN"),amount,purpose,java.time.LocalDate.parse(requestedDate),value(child(tx,"PmtId"),"EndToEndId"));
        return new Parsed(id,payment);
    }
    private static Element onlyChild(Element parent,String name){
        var values=children(parent,name);if(values.size()!=1)throw new IllegalArgumentException("Exactly one "+name+" required");return values.getFirst();
    }
    private static List<Element> children(Element parent,String name){
        var found=new ArrayList<Element>();for(Node n=parent.getFirstChild();n!=null;n=n.getNextSibling())if(n instanceof Element e&&name.equals(e.getLocalName())&&isPainNamespace(e.getNamespaceURI()))found.add(e);return found;
    }
    private static Element optionalChild(Element parent,String name){var values=children(parent,name);if(values.size()>1)throw new IllegalArgumentException("Duplicate "+name);return values.isEmpty()?null:values.getFirst();}
    private static Element child(Element parent,String name){Element found=null;for(Node n=parent.getFirstChild();n!=null;n=n.getNextSibling())if(n instanceof Element e&&name.equals(e.getLocalName())&&isPainNamespace(e.getNamespaceURI())){if(found!=null)throw new IllegalArgumentException("Duplicate "+name);found=e;}if(found==null)throw new IllegalArgumentException("Missing "+name);return found;}
    private static boolean isPainNamespace(String namespace){return "urn:iso:std:iso:20022:tech:xsd:pain.001.001.03".equals(namespace)||NS.equals(namespace);}
    private static String value(Element parent,String name){return child(parent,name).getTextContent().trim();}
}
