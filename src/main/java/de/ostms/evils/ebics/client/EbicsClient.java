package de.ostms.evils.ebics.client;

import de.ostms.evils.ebics.service.EbicsAuthentication;
import de.ostms.evils.ebics.service.HpbCodec;
import org.w3c.dom.*;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.time.*;
import java.util.*;
import java.util.zip.*;

/** Small EBICS 3.0/H005 client matching the EVILS development server. */
public final class EbicsClient {
    public record BankKeys(RSAPublicKey authentication, RSAPublicKey encryption) {}
    public record DownloadedFile(String name, byte[] content) { public byte[] content(){ return content.clone(); } }

    private final EbicsClientConfig config;
    private final EbicsKeyMaterial keys;
    private final EbicsTransport transport;
    private BankKeys bankKeys;
    private boolean paymentBankKeysTrusted;

    public EbicsClient(EbicsClientConfig config, EbicsKeyMaterial keys) {
        this(config,keys,new HttpEbicsTransport(config.endpoint()));
    }
    public EbicsClient(EbicsClientConfig config, EbicsKeyMaterial keys, EbicsTransport transport) {
        this.config=Objects.requireNonNull(config);this.keys=Objects.requireNonNull(keys);this.transport=Objects.requireNonNull(transport);
    }

    public void submitIni() throws Exception {
        var key=(RSAPublicKey)keys.signature().getPublic();
        String data="<SignaturePubKeyOrderData xmlns=\"urn:org:ebics:H005\" xmlns:ds=\"http://www.w3.org/2000/09/xmldsig#\">"+
                keyInfo("SignaturePubKeyInfo","SignatureVersion","A006",key)+"</SignaturePubKeyOrderData>";
        expectOk(exchange(keyRequest("INI",data)));
    }

    public void submitHia() throws Exception {
        String data="<HIARequestOrderData xmlns=\"urn:org:ebics:H005\" xmlns:ds=\"http://www.w3.org/2000/09/xmldsig#\">"+
                keyInfo("AuthenticationPubKeyInfo","AuthenticationVersion","X002",(RSAPublicKey)keys.authentication().getPublic())+
                keyInfo("EncryptionPubKeyInfo","EncryptionVersion","E002",(RSAPublicKey)keys.encryption().getPublic())+"</HIARequestOrderData>";
        expectOk(exchange(keyRequest("HIA",data)));
    }

    /** Retrieves bank keys. Their fingerprints must be compared through an independent bank channel. */
    public BankKeys fetchBankKeys() throws Exception {
        Document response=parse(exchange(keyRequest("HPB",null))); expectOk(response);
        byte[] payload=decrypt(response,keys.encryption().getPrivate());
        Document order=parse(payload);
        var result=new BankKeys(HpbCodec.readKey(order.getDocumentElement(),"AuthenticationPubKeyInfo","AuthenticationVersion","X002"),
                HpbCodec.readKey(order.getDocumentElement(),"EncryptionPubKeyInfo","EncryptionVersion","E002"));
        bankKeys=result;paymentBankKeysTrusted=false;return result;
    }

    public void trustBankKeys(BankKeys value) { bankKeys=Objects.requireNonNull(value);paymentBankKeysTrusted=true; }

    /** Submit a single signed payment to the explicitly negotiated EVILS test-server profile. */
    public String submitPayment(byte[] painXml) throws Exception {
        PaymentWire.parse(painXml);
        if(bankKeys==null||!paymentBankKeysTrusted)throw new IllegalStateException("Trusted bank keys required");
        String payload=PaymentWire.wrap(painXml,keys.signature().getPrivate());
        String encrypted=HpbCodec.encrypt(payload.getBytes(StandardCharsets.UTF_8),bankKeys.encryption());
        encrypted=encrypted.replace("<DataTransfer>","<DataTransfer authenticate=\"true\">");
        String details="<AdminOrderType>BTU</AdminOrderType><BTUOrderParams><Service><ServiceName>SCT</ServiceName><MsgName>pain.001</MsgName></Service><Profile>"+PaymentWire.PROFILE+"</Profile></BTUOrderParams>";
        Document response=parse(exchange(signedRequest(staticHeader(details),"Initialisation","",encrypted)));
        // Unauthenticated success is never accepted; transport failures must not be retried with a new MsgId.
        verify(response);expectOk(response);
        String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(painXml));
        if(!digest.equals(text(response,"RequestDigest")))throw new GeneralSecurityException("Zahlungsbestätigung gehört nicht zu diesem Auftrag");
        return text(response,"ReportText");
    }

    public List<DownloadedFile> downloadStatements(String messageName, LocalDate from, LocalDate to) throws Exception {
        if(!Set.of("camt.053","camt.054").contains(messageName)) throw new IllegalArgumentException("messageName must be camt.053 or camt.054");
        if(bankKeys==null) throw new IllegalStateException("Fetch or trust bank keys first");
        if(from.isAfter(to)||from.plusYears(1).isBefore(to))throw new IllegalArgumentException("Date range must be at most one year");
        String detail="<AdminOrderType>BTD</AdminOrderType><BTDOrderParams><Service><ServiceName>STA</ServiceName><MsgName>"+messageName+"</MsgName></Service><DateRange><Start>"+from+"</Start><End>"+to+"</End></DateRange></BTDOrderParams>";
        Document initial=parse(exchange(signedRequest(staticHeader(detail),"Initialisation","",""))); expectOk(initial); verify(initial);
        String transaction=text(initial,"TransactionID"), transactionKey=text(initial,"TransactionKey");
        int segments=Integer.parseInt(text(initial,"NumSegments"));
        var encoded=new StringBuilder(text(initial,"OrderData"));
        for(int number=2;number<=segments;number++) {
            Document part=parse(exchange(signedRequest("<TransactionID>"+escape(transaction)+"</TransactionID>","Transfer","<SegmentNumber>"+number+"</SegmentNumber>","")));
            expectOk(part);verify(part);encoded.append(text(part,"OrderData"));
        }
        byte[] archive=decrypt(transactionKey,encoded.toString(),keys.encryption().getPrivate());
        List<DownloadedFile> files=unzip(archive);
        Document receipt=parse(exchange(signedRequest("<TransactionID>"+escape(transaction)+"</TransactionID>","Receipt","","<TransferReceipt authenticate=\"true\"><ReceiptCode>0</ReceiptCode></TransferReceipt>")));
        verify(receipt); String receiptCode=returnCodes(receipt).getFirst();
        if(!Set.of("011000","000000").contains(receiptCode))throw new IOException("EBICS receipt failed: "+receiptCode);
        return files;
    }

    public static String fingerprint(PublicKey key) throws GeneralSecurityException {
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(key.getEncoded());
        var out=new StringJoiner(":");for(byte b:digest)out.add("%02X".formatted(b));return out.toString();
    }

    private String exchange(String xml) throws IOException,InterruptedException{return transport.post(xml);}
    private String keyRequest(String order,String data)throws Exception {
        String body=data==null?"":"<DataTransfer><OrderData>"+compress(data.getBytes(StandardCharsets.UTF_8))+"</OrderData></DataTransfer>";
        String xml="<ebicsUnsecuredRequest xmlns=\"urn:org:ebics:H005\" Version=\"H005\" Revision=\"1\"><header authenticate=\"true\"><static>"+
                staticHeader("<AdminOrderType>"+order+"</AdminOrderType>")+"</static><mutable/></header><body>"+body+"</body></ebicsUnsecuredRequest>";
        if("HPB".equals(order)) return signedRequest(staticHeader("<AdminOrderType>HPB</AdminOrderType>"),"Initialisation","","");
        return xml;
    }
    private String staticHeader(String orderDetails) {return "<HostID>"+escape(config.hostId())+"</HostID><PartnerID>"+escape(config.partnerId())+"</PartnerID><UserID>"+escape(config.userId())+"</UserID><Timestamp>"+Instant.now()+"</Timestamp><OrderDetails>"+orderDetails+"</OrderDetails>";}
    private String signedRequest(String stat,String phase,String mutable,String body)throws Exception{return EbicsAuthentication.sign("<ebicsRequest xmlns=\"urn:org:ebics:H005\" Version=\"H005\" Revision=\"1\"><header authenticate=\"true\"><static>"+stat+"</static><mutable><TransactionPhase>"+phase+"</TransactionPhase>"+mutable+"</mutable></header><AuthSignature/><body>"+body+"</body></ebicsRequest>",keys.authentication().getPrivate());}
    private void verify(Document response)throws Exception{EbicsAuthentication.verify(response,bankKeys.authentication());}
    private static void expectOk(String xml)throws Exception{expectOk(parse(xml));}
    private static void expectOk(Document doc)throws IOException{for(String code:returnCodes(doc))if(!"000000".equals(code))throw new IOException("EBICS request failed: "+code+" "+optional(doc,"ReportText"));}
    private static List<String> returnCodes(Document doc)throws IOException{var nodes=doc.getElementsByTagNameNS("*","ReturnCode");if(nodes.getLength()==0)throw new IOException("EBICS response has no return code");var result=new ArrayList<String>();for(int i=0;i<nodes.getLength();i++)result.add(nodes.item(i).getTextContent().trim());return result;}
    private static Document parse(String xml)throws Exception{return parse(xml.getBytes(StandardCharsets.UTF_8));}
    private static Document parse(byte[] xml)throws Exception{return HpbCodec.parse(xml);}
    private static String text(Document doc,String name){return HpbCodec.text(doc.getDocumentElement(),name);}
    private static String optional(Document doc,String name){var n=doc.getElementsByTagNameNS("*",name);return n.getLength()==0?"":n.item(0).getTextContent().trim();}
    private static String compress(byte[] value)throws IOException{var out=new ByteArrayOutputStream();try(var zip=new DeflaterOutputStream(out)){zip.write(value);}return Base64.getEncoder().encodeToString(out.toByteArray());}
    private static byte[] decrypt(Document doc,PrivateKey key)throws Exception{return decrypt(text(doc,"TransactionKey"),text(doc,"OrderData"),key);}
    private static byte[] decrypt(String encryptedKey,String orderData,PrivateKey key)throws Exception {
        var rsa=Cipher.getInstance("RSA/ECB/PKCS1Padding");rsa.init(Cipher.DECRYPT_MODE,key);byte[] secret=rsa.doFinal(Base64.getDecoder().decode(encryptedKey));
        try {var aes=Cipher.getInstance("AES/CBC/NoPadding");aes.init(Cipher.DECRYPT_MODE,new SecretKeySpec(secret,"AES"),new IvParameterSpec(new byte[16]));byte[] padded=aes.doFinal(Base64.getDecoder().decode(orderData));int pad=padded[padded.length-1]&255;if(pad<1||pad>16)throw new GeneralSecurityException("Invalid EBICS padding");try(var in=new InflaterInputStream(new ByteArrayInputStream(padded,0,padded.length-pad))){return in.readAllBytes();}}
        finally{Arrays.fill(secret,(byte)0);}
    }
    private static List<DownloadedFile> unzip(byte[] archive)throws IOException{var files=new ArrayList<DownloadedFile>();try(var zip=new ZipInputStream(new ByteArrayInputStream(archive))){for(ZipEntry e;(e=zip.getNextEntry())!=null;){if(e.isDirectory()||e.getName().contains("..")||e.getName().startsWith("/"))throw new IOException("Unsafe ZIP entry");files.add(new DownloadedFile(e.getName(),zip.readAllBytes()));}}if(files.isEmpty())throw new IOException("Empty statement archive");return List.copyOf(files);}
    private static String keyInfo(String name,String versionName,String version,RSAPublicKey key){return "<"+name+"><PubKeyValue><ds:RSAKeyValue><ds:Modulus>"+integer(key.getModulus())+"</ds:Modulus><ds:Exponent>"+integer(key.getPublicExponent())+"</ds:Exponent></ds:RSAKeyValue></PubKeyValue><"+versionName+">"+version+"</"+versionName+"></"+name+">";}
    private static String integer(BigInteger value){byte[] b=value.toByteArray();if(b[0]==0)b=Arrays.copyOfRange(b,1,b.length);return Base64.getEncoder().encodeToString(b);}
    private static String escape(String value){return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;");}
}
