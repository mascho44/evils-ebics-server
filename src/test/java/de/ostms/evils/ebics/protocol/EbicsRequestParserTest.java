package de.ostms.evils.ebics.protocol;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.*;

class EbicsRequestParserTest {

    private final EbicsRequestParser parser = new EbicsRequestParser();

    @Test
    void parsesH005Request() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <ebicsRequest xmlns="urn:org:ebics:H005" Version="H005">
                  <header>
                    <static>
                      <HostID>EVILSBANK</HostID>
                      <PartnerID>TEST001</PartnerID>
                      <UserID>USER001</UserID>
                      <OrderDetails>
                        <OrderType>HKD</OrderType>
                        <OrderID>ABC123</OrderID>
                      </OrderDetails>
                    </static>
                  </header>
                </ebicsRequest>
                """;

        EbicsRequest r = parser.parse(xml);

        assertEquals("H005", r.version());
        assertEquals("EVILSBANK", r.hostId());
        assertEquals("TEST001", r.partnerId());
        assertEquals("USER001", r.userId());
        assertEquals("HKD", r.orderType());
        assertEquals("ABC123", r.orderId());
    }

    @Test
    void parsesH004Request() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <ebicsRequest xmlns="urn:org:ebics:H004" Version="H004">
                  <header>
                    <static>
                      <HostID>EVILSBANK</HostID>
                      <PartnerID>TEST001</PartnerID>
                      <UserID>USER001</UserID>
                      <OrderDetails>
                        <OrderType>HPB</OrderType>
                        <OrderID>ABC124</OrderID>
                      </OrderDetails>
                    </static>
                  </header>
                </ebicsRequest>
                """;

        EbicsRequest r = parser.parse(xml);

        assertEquals("H004", r.version());
        assertEquals("EVILSBANK", r.hostId());
        assertEquals("HPB", r.orderType());
    }

    @Test
    void rejectsWrongNamespace() {
        String xml = """
                <ebicsRequest xmlns="urn:wrong" Version="H005"/>
                """;

        assertThrows(EbicsProtocolException.class, () -> parser.parse(xml));
    }

    @Test
    void parsesAdministrativeHacOrderType() {
        String xml = """
                <ebicsRequest xmlns="urn:org:ebics:H005" Version="H005">
                  <header><static><HostID>EVILSBANK</HostID><PartnerID>TEST001</PartnerID><UserID>USER001</UserID>
                    <OrderDetails><AdminOrderType>HAC</AdminOrderType><OrderID>ORDER-42</OrderID></OrderDetails>
                  </static></header>
                </ebicsRequest>
                """;
        EbicsRequest request = parser.parse(xml);
        assertEquals("HAC", request.orderType());
        assertEquals("ORDER-42", request.orderId());
    }

    @Test
    void parsesReferencedOrderFromHacOrderParams() {
        String xml = """
                <ebicsRequest xmlns="urn:org:ebics:H005" Version="H005">
                  <header><static><HostID>EVILSBANK</HostID><PartnerID>TEST001</PartnerID><UserID>USER001</UserID>
                    <OrderDetails><AdminOrderType>HAC</AdminOrderType><HACOrderParams><OrderID>ORDER-99</OrderID></HACOrderParams></OrderDetails>
                  </static></header>
                </ebicsRequest>
                """;

        assertEquals("ORDER-99", parser.parse(xml).orderId());
    }

    @Test
    void decodesBase64OrderDataFromTheBody() {
        String innerXml = "<SignaturePubKeyOrderData xmlns=\"urn:org:ebics:H005\"><Marker/></SignaturePubKeyOrderData>";
        String base64 = Base64.getEncoder().encodeToString(deflate(innerXml.getBytes(StandardCharsets.UTF_8)));
        String xml = """
                <ebicsRequest xmlns="urn:org:ebics:H005" Version="H005">
                  <header><static><HostID>EVILSBANK</HostID><PartnerID>TEST001</PartnerID><UserID>USER001</UserID>
                    <OrderDetails><OrderType>INI</OrderType><OrderID>ORDER-INI</OrderID></OrderDetails>
                  </static></header>
                  <body><DataTransfer><OrderData>%s</OrderData></DataTransfer></body>
                </ebicsRequest>
                """.formatted(base64);

        EbicsRequest request = parser.parse(xml);

        assertEquals(innerXml, request.orderDataXml());
    }

    @Test
    void orderDataIsNullWhenTheRequestHasNoBody() {
        String xml = """
                <ebicsRequest xmlns="urn:org:ebics:H005" Version="H005">
                  <header><static><HostID>EVILSBANK</HostID></static></header>
                </ebicsRequest>
                """;

        assertNull(parser.parse(xml).orderDataXml());
    }

    @Test
    void parsesEbicsUnsecuredRequestUsedByRealClientsForIniAndHia() {
        String innerXml = "<SignaturePubKeyOrderData xmlns=\"urn:org:ebics:H005\"><Marker/></SignaturePubKeyOrderData>";
        String base64 = Base64.getEncoder().encodeToString(deflate(innerXml.getBytes(StandardCharsets.UTF_8)));
        String xml = """
                <ebicsUnsecuredRequest xmlns="urn:org:ebics:H005" Version="H005">
                  <header authenticate="true">
                    <static><HostID>EVILSBANK</HostID><PartnerID>TEST001</PartnerID><UserID>USER001</UserID>
                      <OrderDetails><AdminOrderType>INI</AdminOrderType></OrderDetails>
                      <SecurityMedium>0000</SecurityMedium>
                    </static>
                    <mutable/>
                  </header>
                  <body><DataTransfer><OrderData>%s</OrderData></DataTransfer></body>
                </ebicsUnsecuredRequest>
                """.formatted(base64);

        EbicsRequest request = parser.parse(xml);

        assertEquals("INI", request.orderType());
        assertEquals(innerXml, request.orderDataXml());
    }

    @Test
    void parsesEbicsNoPubKeyDigestsRequestUsedByRealClientsForHpb() {
        String xml = """
                <ebicsNoPubKeyDigestsRequest xmlns="urn:org:ebics:H005" Version="H005">
                  <header authenticate="true">
                    <static><HostID>EVILSBANK</HostID><PartnerID>TEST001</PartnerID><UserID>USER001</UserID>
                      <OrderDetails><AdminOrderType>HPB</AdminOrderType></OrderDetails>
                      <SecurityMedium>0000</SecurityMedium>
                    </static>
                    <mutable/>
                  </header>
                  <body/>
                </ebicsNoPubKeyDigestsRequest>
                """;

        EbicsRequest request = parser.parse(xml);

        assertEquals("HPB", request.orderType());
        assertEquals("EVILSBANK", request.hostId());
    }

    @Test
    void rejectsDoctype() {
        String xml = """
                <!DOCTYPE foo [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
                <ebicsRequest xmlns="urn:org:ebics:H005" Version="H005">
                  <header><static><HostID>&xxe;</HostID></static></header>
                </ebicsRequest>
                """;

        assertThrows(EbicsProtocolException.class, () -> parser.parse(xml));
    }

    // ---- Size caps (see MAX_REQUEST_BYTES / MAX_ORDER_DATA_BYTES) ----

    /** A request body is never legitimately larger than 1 MB (the payment-upload path already
     * caps at the same limit via HpbCodec.parse) - reject before parsing instead of letting one
     * request pin arbitrary memory. */
    @Test
    void rejectsRequestLargerThanOneMegabyte() {
        String padding = "x".repeat(1_048_577);
        String xml = "<ebicsRequest xmlns=\"urn:org:ebics:H005\" Version=\"H005\"><header><static><HostID>EVILSBANK</HostID><Note>"
                + padding + "</Note></static></header></ebicsRequest>";

        assertThrows(EbicsProtocolException.class, () -> parser.parse(xml));
    }

    /** A few KB of highly compressible base64 OrderData must not expand to gigabytes in memory -
     * inflate() caps the decompressed size (see MAX_ORDER_DATA_BYTES), so a decompression bomb
     * fails fast with a protocol error instead of an OutOfMemoryError. */
    @Test
    void rejectsDecompressionBombInOrderData() {
        byte[] hundredMegabytesOfZeros = new byte[100 * 1024 * 1024];
        String base64 = Base64.getEncoder().encodeToString(deflate(hundredMegabytesOfZeros));
        String xml = """
                <ebicsRequest xmlns="urn:org:ebics:H005" Version="H005">
                  <header><static><HostID>EVILSBANK</HostID><PartnerID>TEST001</PartnerID><UserID>USER001</UserID>
                    <OrderDetails><OrderType>INI</OrderType><OrderID>ORDER-INI</OrderID></OrderDetails>
                  </static></header>
                  <body><DataTransfer><OrderData>%s</OrderData></DataTransfer></body>
                </ebicsRequest>
                """.formatted(base64);

        assertThrows(EbicsProtocolException.class, () -> parser.parse(xml));
    }

    private static byte[] deflate(byte[] data) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        deflater.setInput(data);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
        byte[] buffer = new byte[4096];
        while (!deflater.finished()) {
            out.write(buffer, 0, deflater.deflate(buffer));
        }
        deflater.end();
        return out.toByteArray();
    }
}
