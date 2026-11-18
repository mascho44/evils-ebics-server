package de.ostms.evils.ebics.protocol;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Parses an incoming EBICS XML request into an {@link EbicsRequest}: host, partner, user, order type
 * or business transaction format, date range, BTD parameters, transaction phase and the base64/zlib encoded
 * order data of key-management uploads. It accepts the three root elements a client uses
 * ({@code ebicsRequest}, {@code ebicsUnsecuredRequest} for INI/HIA and {@code ebicsNoPubKeyDigestsRequest}
 * for HPB) in both {@code H004} and {@code H005}. It only reads the structure; signatures and permissions
 * are checked later.
 */
public class EbicsRequestParser {

    private static final String H005_NS = "urn:org:ebics:H005";
    /** H004 (EBICS 2.5) key management (INI/HIA/HPB) is accepted alongside H005; the transport
     * crypto (X002/E002) and request/response shape are identical between the two - see
     * EbicsResponseBuilder. */
    private static final String H004_NS = "urn:org:ebics:H004";

    /** A real EBICS client uses different root elements depending on the order type:
     * ebicsRequest for signed order/file-transfer types (HKD, HPD, HAC, ...), ebicsUnsecuredRequest
     * for INI/HIA (no signature exists yet - that is the whole point of those two order types),
     * and ebicsNoPubKeyDigestsRequest for HPB (the customer has no bank key digests to send yet
     * either). All three carry the same header/static/OrderDetails/(OrderType|AdminOrderType)
     * shape this parser already reads generically. */
    private static final java.util.Set<String> VALID_ROOT_ELEMENTS = java.util.Set.of(
            "ebicsRequest", "ebicsUnsecuredRequest", "ebicsNoPubKeyDigestsRequest");

    /** Same 1 MB envelope cap as HpbCodec.parse (the payment-upload path) - a request is never
     * legitimately larger, and an unbounded String body would otherwise let a single request
     * pin arbitrary amounts of memory before any validation runs. */
    private static final int MAX_REQUEST_BYTES = 1_048_576;
    /** Cap on the inflated OrderData (INI/HIA key uploads are a few KB; nothing legitimate gets
     * near this). Without it, a few KB of base64 zlib bomb would expand to gigabytes in memory. */
    private static final int MAX_ORDER_DATA_BYTES = 10 * 1_048_576;

    public EbicsRequest parse(String xml) {
        try {
            if (xml.getBytes(StandardCharsets.UTF_8).length > MAX_REQUEST_BYTES) {
                throw new EbicsProtocolException("Request exceeds " + MAX_REQUEST_BYTES + " bytes");
            }
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);

            Document doc = f.newDocumentBuilder()
                    .parse(new InputSource(new StringReader(xml)));

            Element root = doc.getDocumentElement();

            if (!VALID_ROOT_ELEMENTS.contains(root.getLocalName())) {
                throw new EbicsProtocolException("Unsupported root element: " + root.getLocalName());
            }
            if (!H005_NS.equals(root.getNamespaceURI()) && !H004_NS.equals(root.getNamespaceURI())) {
                throw new EbicsProtocolException(
                        "Unsupported namespace: " + root.getNamespaceURI());
            }

            String version = attr(root, "Version");

            Element header = child(root, "header");
            Element stat = child(header, "static");

            String hostId = text(stat, "HostID");
            String partnerId = text(stat, "PartnerID");
            String userId = text(stat, "UserID");

            Element details = child(stat, "OrderDetails");
            String orderType = details == null ? null : text(details, "OrderType");
            if (orderType == null) orderType = details == null ? null : text(details, "AdminOrderType");
            String orderId = details == null ? null : text(details, "OrderID");
            // H005 clients commonly nest the referenced order inside HACOrderParams rather
            // than directly below OrderDetails. Preserve it for the audit trail and GUI.
            if (orderId == null && "HAC".equalsIgnoreCase(orderType)) {
                orderId = text(child(details, "HACOrderParams"), "OrderID");
            }

            String orderDataXml = orderData(root);

            Element mutable = child(header, "mutable");
            String transactionPhase = text(mutable, "TransactionPhase");
            String transactionId = text(stat, "TransactionID");

            String btdServiceName = null;
            String btdMsgName = null;
            java.time.LocalDate dateFrom = null;
            java.time.LocalDate dateTo = null;
            Element btdOrderParams = child(details, "BTDOrderParams");
            if (btdOrderParams != null) {
                Element service = child(btdOrderParams, "Service");
                btdServiceName = text(service, "ServiceName");
                btdMsgName = text(service, "MsgName");
                Element dateRange = child(btdOrderParams, "DateRange");
                if (dateRange != null) {
                    String start = text(dateRange, "Start");
                    String end = text(dateRange, "End");
                    dateFrom = start == null ? null : java.time.LocalDate.parse(start);
                    dateTo = end == null ? null : java.time.LocalDate.parse(end);
                }
            }

            if(dateFrom==null&&dateTo==null){
                Element range=child(child(details,"StandardOrderParams"),"DateRange");
                if(range!=null){String start=text(range,"Start"),end=text(range,"End");dateFrom=start==null?null:java.time.LocalDate.parse(start);dateTo=end==null?null:java.time.LocalDate.parse(end);}
            }
            return new EbicsRequest(
                    version, hostId, partnerId, userId, orderType, orderId, xml, orderDataXml,
                    transactionPhase, transactionId, btdServiceName, btdMsgName, dateFrom, dateTo);

        } catch (EbicsProtocolException e) {
            throw e;
        } catch (Exception e) {
            throw new EbicsProtocolException("Invalid XML request", e);
        }
    }

    /** body/DataTransfer/OrderData carries the actual order payload (e.g. the customer's
     * SignaturePubKeyOrderData for INI, HIARequestOrderData for HIA) base64-encoded and, per the
     * real EBICS wire format, zlib-deflated (see org.kopi.ebics.client.Utils#zip on the client
     * side) even for these unencrypted key-management uploads - null if the request has no body
     * (most order types). Only the raw XML text is decoded here; callers parse further as needed
     * (see PubKeyValueXml). */
    private static String orderData(Element root) {
        Element body = child(root, "body");
        Element transfer = body == null ? null : child(body, "DataTransfer");
        Element orderData = transfer == null ? null : child(transfer, "OrderData");
        if (orderData == null) return null;
        String base64 = orderData.getTextContent().trim();
        if (base64.isEmpty()) return null;
        byte[] decoded;
        try {
            decoded = Base64.getMimeDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new EbicsProtocolException("OrderData is not valid base64", e);
        }
        try {
            return new String(inflate(decoded), StandardCharsets.UTF_8);
        } catch (DataFormatException e) {
            throw new EbicsProtocolException("OrderData is not valid deflate-compressed data", e);
        }
    }

    private static byte[] inflate(byte[] data) throws DataFormatException {
        Inflater inflater = new Inflater();
        inflater.setInput(data);
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length * 3);
        byte[] buffer = new byte[4096];
        while (!inflater.finished()) {
            int n = inflater.inflate(buffer);
            if (n == 0 && inflater.needsInput()) break;
            if (out.size() + n > MAX_ORDER_DATA_BYTES) {
                inflater.end();
                throw new EbicsProtocolException("OrderData exceeds " + MAX_ORDER_DATA_BYTES + " bytes when decompressed");
            }
            out.write(buffer, 0, n);
        }
        inflater.end();
        return out.toByteArray();
    }

    private static Element child(Element parent, String localName) {
        if (parent == null) return null;
        var nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element e &&
                    localName.equals(e.getLocalName())) {
                return e;
            }
        }
        return null;
    }

    private static String text(Element parent, String localName) {
        Element e = child(parent, localName);
        return e == null ? null : e.getTextContent().trim();
    }

    private static String attr(Element e, String name) {
        return e.hasAttribute(name) ? e.getAttribute(name) : null;
    }
}
