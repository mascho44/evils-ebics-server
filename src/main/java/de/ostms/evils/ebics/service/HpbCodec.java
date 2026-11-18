package de.ostms.evils.ebics.service;

import java.io.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.*;
import java.util.*;
import java.util.zip.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

/** EBICS E002 key transport: zlib, AES-128-CBC/X9.23 and RSA PKCS#1 v1.5. */
public final class HpbCodec {
    private HpbCodec() {}
    public static Document parse(byte[] xml) throws Exception {
        if (xml.length > 1048576) throw new IllegalArgumentException("XML exceeds limit");
        var f = DocumentBuilderFactory.newInstance(); f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setXIncludeAware(false); f.setExpandEntityReferences(false);
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    }
    public static String text(Element parent, String name) {
        var nodes = parent.getElementsByTagNameNS("*", name);
        if (nodes.getLength() != 1) throw new IllegalArgumentException("Expected exactly one " + name);
        return nodes.item(0).getTextContent().trim();
    }
    public static RSAPublicKey decodeKey(String value) throws Exception {
        return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(value)));
    }
    public static RSAPublicKey readKey(Element root, String info, String versionTag, String version) throws Exception {
        var nodes = root.getElementsByTagNameNS("*", info);
        if (nodes.getLength() != 1) throw new IllegalArgumentException("Missing or duplicate " + info);
        var e = (Element) nodes.item(0);
        if (!version.equals(text(e, versionTag))) throw new IllegalArgumentException("Unsupported key version");
        if(e.getElementsByTagNameNS("*","X509Certificate").getLength()==1) {
            var cert=(java.security.cert.X509Certificate)java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(Base64.getDecoder().decode(text(e,"X509Certificate").replaceAll("\\s",""))));
            var rsa=(RSAPublicKey)cert.getPublicKey();
            if(rsa.getModulus().bitLength()<2048 || rsa.getModulus().bitLength()>4096) throw new IllegalArgumentException("Invalid RSA size");
            return rsa;
        }
        var key = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(
                new BigInteger(1, Base64.getDecoder().decode(text(e,"Modulus").replaceAll("\\s", ""))),
                new BigInteger(1, Base64.getDecoder().decode(text(e,"Exponent").replaceAll("\\s", "")))));
        if (key.getModulus().bitLength() < 2048 || key.getModulus().bitLength() > 4096 || !key.getPublicExponent().testBit(0))
            throw new IllegalArgumentException("Invalid RSA key");
        return key;
    }
    public static Document orderData(String request) throws Exception {
        var outer = parse(request.getBytes(StandardCharsets.UTF_8));
        var compressed = Base64.getDecoder().decode(text(outer.getDocumentElement(), "OrderData").replaceAll("\\s", ""));
        try (var in = new InflaterInputStream(new ByteArrayInputStream(compressed))) {
            return parse(in.readNBytes(1048577));
        }
    }
    public static String digest(RSAPublicKey key) throws Exception {
        return b64(MessageDigest.getInstance("SHA-256").digest((key.getPublicExponent().toString(16)+" "+key.getModulus().toString(16)).getBytes(StandardCharsets.US_ASCII)));
    }
    private static String integer(BigInteger value) {
        byte[] bytes = value.toByteArray();
        return b64(bytes[0] == 0 ? Arrays.copyOfRange(bytes,1,bytes.length) : bytes);
    }
    private static String info(String name, String versionTag, String version, RSAPublicKey key) {
        return "<"+name+"><PubKeyValue><ds:RSAKeyValue><ds:Modulus>"+integer(key.getModulus())+"</ds:Modulus><ds:Exponent>"+integer(key.getPublicExponent())+"</ds:Exponent></ds:RSAKeyValue></PubKeyValue><"+versionTag+">"+version+"</"+versionTag+"></"+name+">";
    }
    public static String response(RSAPublicKey client, RSAPublicKey auth, RSAPublicKey enc) throws Exception {
        return response(client,auth,enc,"EVILSBANK");
    }
    public static String response(RSAPublicKey client, RSAPublicKey auth, RSAPublicKey enc, String hostId) throws Exception {
        return response(client,auth,enc,hostId,null,null);
    }
    public static String response(RSAPublicKey client, RSAPublicKey auth, RSAPublicKey enc, String hostId, String authCert, String encCert) throws Exception {
        if (!hostId.matches("[A-Za-z0-9]{1,35}")) throw new IllegalArgumentException("Invalid HostID");
        String payload = "<HPBResponseOrderData xmlns=\"urn:org:ebics:H005\" xmlns:ds=\"http://www.w3.org/2000/09/xmldsig#\">"
                +info("AuthenticationPubKeyInfo","AuthenticationVersion","X002",auth)
                +info("EncryptionPubKeyInfo","EncryptionVersion","E002",enc)+"<HostID>"+hostId+"</HostID></HPBResponseOrderData>";
        if(authCert!=null && encCert!=null) {
            payload=payload.replaceFirst("<PubKeyValue>.*?</PubKeyValue>","<ds:X509Data><ds:X509Certificate>"+authCert+"</ds:X509Certificate></ds:X509Data>");
            payload=payload.replaceFirst("<PubKeyValue>.*?</PubKeyValue>","<ds:X509Data><ds:X509Certificate>"+encCert+"</ds:X509Certificate></ds:X509Data>");
        }
        return "<ebicsKeyManagementResponse xmlns=\"urn:org:ebics:H005\" Version=\"H005\" Revision=\"1\"><header authenticate=\"true\"><static/><mutable><ReturnCode>000000</ReturnCode><ReportText>[EBICS_OK] OK</ReportText></mutable></header><body>"+encrypt(payload.getBytes(StandardCharsets.UTF_8),client)+"<ReturnCode authenticate=\"true\">000000</ReturnCode></body></ebicsKeyManagementResponse>";
    }
    public static String encrypt(byte[] payload, RSAPublicKey client) throws Exception {
        var output = new ByteArrayOutputStream();
        try (var zip = new DeflaterOutputStream(output)) { zip.write(payload); }
        byte[] data = output.toByteArray(); int padding = 16-data.length%16;
        data = Arrays.copyOf(data,data.length+padding); data[data.length-1]=(byte)padding;
        byte[] secret = new byte[16]; new SecureRandom().nextBytes(secret);
        try {
            var aes = Cipher.getInstance("AES/CBC/NoPadding");
            aes.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(secret,"AES"),new IvParameterSpec(new byte[16]));
            var rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding"); rsa.init(Cipher.ENCRYPT_MODE,client);
            return "<DataTransfer><DataEncryptionInfo authenticate=\"true\"><EncryptionPubKeyDigest Version=\"E002\" Algorithm=\"http://www.w3.org/2001/04/xmlenc#sha256\">"
                +digest(client)+"</EncryptionPubKeyDigest><TransactionKey>"+b64(rsa.doFinal(secret))+"</TransactionKey></DataEncryptionInfo><OrderData>"
                +b64(aes.doFinal(data))+"</OrderData></DataTransfer>";
        } finally { Arrays.fill(secret,(byte)0); }
    }
    public static String b64(byte[] data) { return Base64.getEncoder().encodeToString(data); }
}
