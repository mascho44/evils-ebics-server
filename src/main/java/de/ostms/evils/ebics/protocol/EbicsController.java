package de.ostms.evils.ebics.protocol;

import de.ostms.evils.ebics.domain.Account;
import de.ostms.evils.ebics.error.EbicsErrorCode;
import de.ostms.evils.ebics.camt.Camt053Generator;
import de.ostms.evils.ebics.camt.Camt052Generator;
import de.ostms.evils.ebics.camt.Camt054Generator;
import de.ostms.evils.ebics.camt.Pain002Generator;
import de.ostms.evils.ebics.service.BankKeyService;
import de.ostms.evils.ebics.service.BankService;
import de.ostms.evils.ebics.service.HacService;
import de.ostms.evils.ebics.service.HpbCodec;
import de.ostms.evils.ebics.service.KeyManagementService;
import de.ostms.evils.ebics.service.ProtocolLogService;
import de.ostms.evils.ebics.service.StatementService;
import de.ostms.evils.ebics.service.PaymentOrderService;
import de.ostms.evils.ebics.service.LcDocumentService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.HexFormat;
import java.io.ByteArrayOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The single EBICS endpoint ({@code /ebicsweb}). A POST is parsed by {@link EbicsRequestParser}, checked
 * against the subscriber's permissions and dispatched by protocol version and order type: HEV version
 * discovery, key management (INI/HIA/HPB), HTD, the BTD downloads of EBICS 3.0 ({@code H005}), the classic
 * order types of EBICS 2.5 ({@code H004}: STA, VMK, C52/C53/C54, Z52/Z53/Z54, Z01 and the CCT upload), HAC,
 * VEU and payment uploads. Answers are built by {@link EbicsResponseBuilder}; a GET only returns an
 * information page. Order types of the German profile that are not implemented are answered with
 * {@code EBICS_UNSUPPORTED_ORDER_TYPE} instead of a misleading success.
 */
@RestController
public class EbicsController {
    @org.springframework.beans.factory.annotation.Autowired private de.ostms.evils.ebics.service.VeuService veu;
    @org.springframework.beans.factory.annotation.Autowired private de.ostms.evils.ebics.service.PaymentReportService reports;
    @org.springframework.beans.factory.annotation.Autowired
    private de.ostms.evils.ebics.service.PaymentUploadService paymentUploads;

    @org.springframework.beans.factory.annotation.Autowired private de.ostms.evils.ebics.swift.Mt940Generator mt940Generator;
    @org.springframework.beans.factory.annotation.Autowired private de.ostms.evils.ebics.swift.Mt942Generator mt942Generator;

    private static final SecureRandom RANDOM = new SecureRandom();

    /** EBICS 2.5 (H004) order types are the classic per-type identifiers instead of BTD/BTU
     *: STA = MT940 statement, C52/C53/C54 = camt.052/053/054, Z01 = pain.002 status
     * report - all downloads - and CCT = SEPA credit transfer (pain.001), the one upload. Since #42 also
     * VMK = MT942 intraday report and Z52/Z53/Z54 = the camt files ZIP-packed, one XML per account. */
    private static final java.util.Set<String> CLASSIC_DOWNLOAD_TYPES = java.util.Set.of("STA", "VMK", "C52", "C53", "C54", "Z52", "Z53", "Z54", "Z01");
    /** Classic order types of the German EBICS 2.5 profile this mock does not implement (yet) -
     * answered with EBICS_UNSUPPORTED_ORDER_TYPE instead of a misleading bare success. */
    private static final java.util.Set<String> CLASSIC_UNSUPPORTED_TYPES = java.util.Set.of("CDD", "CDB", "CCU");

    private final EbicsRequestParser parser = new EbicsRequestParser();
    private final EbicsResponseBuilder responseBuilder = new EbicsResponseBuilder();
    private final BankService bankService;
    private final BankKeyService bankKeyService;
    private final HacService hacService;
    private final KeyManagementService keyManagementService;
    private final ProtocolLogService protocolLogService;
    private final StatementService statementService;
    private final Camt053Generator camt053Generator;
    private final Camt052Generator camt052Generator;
    private final Camt054Generator camt054Generator;
    private final Pain002Generator pain002Generator;
    private final PaymentOrderService paymentOrderService;
    private final LcDocumentService lcDocumentService;

    /** MsgName-Werte, unter denen ein EBICS-Client MT7xx-Dokumentenakkreditiv-Nachrichten per BTD
     * abrufen kann (siehe mt7xx()/htd()) - KEIN realer EBICS-/ISO-20022-Standard (MT7xx-Handelsfinanzierung
     * laeuft in der echten Welt ueber SWIFT FIN/SWIFTNet Trade Services, nicht ueber EBICS), sondern
     * eine bewusste, als solche dokumentierte Erweiterung dieses Mock-Servers . */
    private static final java.util.Set<String> MT7XX_MSG_NAMES = java.util.Set.of("MT700", "MT707", "MT710", "MT760");

    public EbicsController(BankService bankService, BankKeyService bankKeyService, HacService hacService,
                            KeyManagementService keyManagementService, ProtocolLogService protocolLogService,
                            StatementService statementService, Camt053Generator camt053Generator, Camt052Generator camt052Generator,
                            Camt054Generator camt054Generator, Pain002Generator pain002Generator,
                            PaymentOrderService paymentOrderService, LcDocumentService lcDocumentService) {
        this.bankService = bankService;
        this.bankKeyService = bankKeyService;
        this.hacService = hacService;
        this.keyManagementService = keyManagementService;
        this.protocolLogService = protocolLogService;
        this.statementService = statementService;
        this.camt053Generator = camt053Generator;
        this.camt052Generator = camt052Generator;
        this.camt054Generator = camt054Generator;
        this.pain002Generator = pain002Generator;
        this.paymentOrderService = paymentOrderService;
        this.lcDocumentService = lcDocumentService;
    }

    @GetMapping(value = "/ebicsweb", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> endpointInformation() {
        return ResponseEntity.ok("""
                <!doctype html>
                <html lang="de"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
                <title>EVILS EBICS Server</title><style>
                body{margin:0;min-height:100vh;display:grid;place-items:center;background:#09111f;color:#e8eef9;font:16px/1.55 system-ui,sans-serif}
                main{max-width:620px;margin:24px;padding:34px;border:1px solid #243550;border-radius:16px;background:#111d31;box-shadow:0 20px 50px #0005}
                h1{margin:0 0 8px;font-size:25px}strong{color:#4f9cff}p{color:#b8c7df}code{display:block;margin:20px 0;padding:14px;border-radius:9px;background:#09111f;color:#b7f6d4;overflow-wrap:anywhere}.badge{color:#b7f6d4;font-size:13px}
                </style></head><body><main><div class="badge">● EBICS-Endpunkt aktiv</div><h1><strong>EVILS</strong> EBICS Server</h1>
                <p>Diese Adresse ist für EBICS-Clients bestimmt. Browser-Aufrufe sind willkommen, führen aber keine Bankkommunikation aus.</p>
                <code>POST https://ebics.example.com/ebicsweb<br>Content-Type: application/xml</code>
                <p>Die Admin-Oberfläche erreichen Sie über die Startseite dieser Domain.</p></main></body></html>
                """
        );
    }

    @PostMapping(
            value = "/ebicsweb",
            consumes = {MediaType.APPLICATION_XML_VALUE, "text/xml"},
            produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> receive(@RequestBody String xml) {
        // HEV is intentionally outside the H005 envelope.  Clients such as
        // SFirm use this unauthenticated H000 request to discover the
        // protocol version before sending INI/HIA/HPB.
        try {
            var root = de.ostms.evils.ebics.service.HpbCodec.parse(xml.getBytes(StandardCharsets.UTF_8)).getDocumentElement();
            if ("ebicsHEVRequest".equals(root.getLocalName())
                    && "http://www.ebics.org/H000".equals(root.getNamespaceURI())) {
                var hosts = root.getElementsByTagNameNS("http://www.ebics.org/H000", "HostID");
                String hostId = hosts.getLength() == 1 ? hosts.item(0).getTextContent().trim() : "";
                boolean known = bankService.findBank(hostId).isPresent();
                return ResponseEntity.ok(responseBuilder.hev(known));
            }
        } catch (Exception ignored) {
            // The normal H005 parser below returns the protocol error for malformed XML.
        }
        // The EVILS upload profile is encrypted; do not feed it to the legacy INI inflater.
        try {
            var root=de.ostms.evils.ebics.service.HpbCodec.parse(xml.getBytes(StandardCharsets.UTF_8)).getDocumentElement();
            var orders=root.getElementsByTagNameNS("urn:org:ebics:H005","AdminOrderType");
            // "BTU" is the EBICS-3.0-native upload identifier; "FUL" is the legacy per-type
            // identifier that ebics-java-client (and other clients still built against the
            // classic OrderType enum, not the newer Service/Scope/MsgName BTU model) sends
            // instead. Without this second check, such a client's Init request falls through
            // to the legacy order-type dispatch below (which has no FUL case), producing a
            // bare success() response with no TransactionID - the client then cannot build a
            // valid Transfer-phase request at all.
            boolean upload=false;for(int i=0;i<orders.getLength();i++){String t=orders.item(i).getTextContent().trim();if("BTU".equals(t)||"FUL".equals(t))upload=true;}
            // EBICS 2.5 (H004) names the order directly: OrderType=CCT (SEPA credit transfer).
            var classicOrders=root.getElementsByTagNameNS("urn:org:ebics:H004","OrderType");
            for(int i=0;i<classicOrders.getLength();i++){if("CCT".equals(classicOrders.item(i).getTextContent().trim()))upload=true;}
            String uploadProtocol="urn:org:ebics:H004".equals(root.getNamespaceURI())?"H004":"H005";
            if(!upload) upload=paymentUploads.isPaymentTransaction(headerValue(root,"TransactionID"));
            if(upload){
                // Per-participant upload permission: checked on every phase that
                // names the user; a rejected Init means no transaction exists for later phases.
                String uploadHost=headerValue(root,"HostID"), uploadUser=headerValue(root,"UserID");
                if(uploadHost!=null&&uploadUser!=null
                        &&bankService.findParticipant(uploadHost,uploadUser).filter(p->!p.isUploadAllowed()).isPresent()){
                    protocolLogService.uploadRejected(uploadHost, headerValue(root,"PartnerID"), uploadUser, "Upload-Berechtigung fehlt");
                    return ResponseEntity.ok(paymentUploads.responseFor(uploadProtocol, EbicsErrorCode.EBICS_AUTHORISATION_ORDERTYPE_FAILED.code(),
                            "Zahlungs-Upload für diesen Benutzer nicht berechtigt."));
                }
                try{return ResponseEntity.ok(paymentUploads.receive(xml));}
                catch(Exception e){
                    // Diagnostic metadata only: never log payment XML, amounts, counterparties or keys.
                    String phase = root.getElementsByTagNameNS("urn:org:ebics:H005", "TransactionPhase").getLength()==0 ? "?" : root.getElementsByTagNameNS("urn:org:ebics:H005", "TransactionPhase").item(0).getTextContent().trim();
                    String category = uploadErrorCategory(e);
                    String detail = safeUploadDiagnostic(e);
                    System.err.printf("EBICS upload rejected: phase=%s error=%s category=%s detail=%s%n", phase, e.getClass().getSimpleName(), category, detail);
                    protocolLogService.uploadRejected(headerValue(root, "HostID"), headerValue(root, "PartnerID"), headerValue(root, "UserID"), category + " (" + detail + ")");
                    return ResponseEntity.ok(paymentUploads.responseFor(uploadProtocol,"091116","Zahlung abgelehnt: Signatur, Berechtigung, Datum oder Auftragsdaten prüfen."));
                }
            }
        }catch(Exception e){return ResponseEntity.badRequest().body("Invalid payment envelope");}
        EbicsRequest request;
        try {
            request = parser.parse(xml);
        } catch (EbicsProtocolException e) {
            // Order type unknown - the request couldn't even be parsed far enough to read it.
            return ResponseEntity.ok(responseBuilder.error(EbicsErrorCode.EBICS_INVALID_REQUEST.code()));
        }
        try {
            return process(request);
        } catch (EbicsProtocolException e) {
            protocolLogService.rejected(request, e.getMessage());
            String code = EbicsErrorCode.EBICS_INVALID_REQUEST.code();
            String response = isKeyManagementOrderType(request.orderType())
                    ? responseBuilder.keyManagementError(code, e.getMessage(), request)
                    : responseBuilder.error(code, request);
            return ResponseEntity.ok(response);
        }
    }

    private static String uploadErrorCategory(Exception e) {
        String m = e.getMessage() == null ? "" : e.getMessage().toLowerCase(java.util.Locale.ROOT);
        if (m.contains("profile")) return "profile";
        if (m.contains("participant") || m.contains("initialized") || m.contains("approval")) return "participant";
        if (m.contains("signature") || m.contains("auth")) return "signature";
        if (m.contains("decrypt") || m.contains("encrypted") || m.contains("padding")) return "encryption";
        if (m.contains("payment") || m.contains("pain")) return "payment-data";
        if (m.contains("account")) return "account";
        return "protocol";
    }

    // Payment XML, account data, amounts, signatures and keys are deliberately never persisted.
    // This is restricted to stable server-side validation labels to make client-format issues diagnosable.
    private static String safeUploadDiagnostic(Exception e) {
        String detail = e.getMessage() == null ? "unspecified" : e.getMessage();
        detail = detail.replaceAll("[^A-Za-z0-9 .:_-]", " ").trim().replaceAll(" +", " ");
        return detail.isEmpty() ? "unspecified" : detail.substring(0, Math.min(detail.length(), 100));
    }

    private static String headerValue(org.w3c.dom.Element root, String localName) {
        var nodes = root.getElementsByTagNameNS("*", localName);
        return nodes.getLength() == 0 ? null : nodes.item(0).getTextContent().trim();
    }

    private static boolean isKeyManagementOrderType(String orderType) {
        return "INI".equalsIgnoreCase(orderType) || "HIA".equalsIgnoreCase(orderType) || "HPB".equalsIgnoreCase(orderType);
    }

    /** Real EBICS requires every download order to carry the subscriber's X002 transport
     * signature - unlike INI/HIA/HPB, which are the unsecured request types by definition (no
     * signature exists yet, that is the whole point of key initialization). The upload, VEU and
     * pain.002/HAC paths already verify it (see PaymentUploadService/VeuService/
     * PaymentReportService); this closes the remaining gap for BTD, HTD, HPD, HKD and the H004
     * classic download types, so an anonymous caller who merely knows HostID/PartnerID/UserID
     * can no longer trigger statement generation and download transactions. The response payload
     * was already E002-encrypted to the participant's key, so this is authentication of the
     * requester, not a new confidentiality guarantee. HAC and BTD/pain.002 verify again inside
     * reports.download - idempotent, kept for one uniform code path here. */
    private void verifyDownloadSignature(EbicsRequest request) {
        String orderType = request.orderType();
        if (orderType == null) return;
        String type = orderType.toUpperCase(java.util.Locale.ROOT);
        if (!("BTD".equals(type) || "HTD".equals(type) || "HPD".equals(type) || "HKD".equals(type))
                && !(request.version() != null && request.version().equals("H004") && CLASSIC_DOWNLOAD_TYPES.contains(type))) return;
        try {
            EbicsAuthentication.verify(HpbCodec.parse(request.xml().getBytes(StandardCharsets.UTF_8)),
                    keyManagementService.activeAuthenticationKey(request.userId()));
        } catch (EbicsProtocolException e) {
            protocolLogService.rejected(request, "Anfrage ohne gueltige Signatur abgewiesen");
            throw new EbicsProtocolException("Download requires a valid request signature");
        } catch (Exception e) {
            protocolLogService.rejected(request, "Ungueltige Anfrage-Signatur: " + e.getClass().getSimpleName());
            throw new EbicsProtocolException("Invalid request signature");
        }
    }

    /** BTD downloads need the participant's download permission; an MT7xx
     * trade-finance pull (ServiceName=TRC) additionally needs the separate LC permission. */
    private static boolean isClassicDownload(EbicsRequest request) {
        return "H004".equals(request.version()) && request.orderType() != null
                && CLASSIC_DOWNLOAD_TYPES.contains(request.orderType().toUpperCase(java.util.Locale.ROOT));
    }

    private boolean downloadDenied(EbicsRequest request) {
        if (request.userId() == null) return false;
        if (!"BTD".equalsIgnoreCase(request.orderType()) && !isClassicDownload(request)) return false;
        var participant = bankService.findParticipant(request.hostId(), request.userId());
        if (participant.isEmpty()) return false;
        boolean mt7xx = request.btdMsgName() != null
                && MT7XX_MSG_NAMES.contains(request.btdMsgName().toUpperCase(java.util.Locale.ROOT));
        return !participant.get().isDownloadAllowed() || (mt7xx && !participant.get().isLcDownloadAllowed());
    }

    private ResponseEntity<String> process(EbicsRequest request) {

        if (request.orderType()!=null && java.util.Set.of("HVU","HVZ","HVD","HVT","HVE","HVS").contains(request.orderType())) {
            try {
                String response=veu.receive(request);
                protocolLogService.accepted(request);
                return ResponseEntity.ok(response);
            } catch (Exception e) {
                protocolLogService.rejected(request,"VEU: "+e.getClass().getSimpleName()+": "+e.getMessage());
                try {return ResponseEntity.ok(paymentUploads.response("091116","VEU-Auftrag abgelehnt: Berechtigung, Auftragsdaten oder Unterschrift prüfen."));}
                catch(Exception signingError){throw new EbicsProtocolException("Could not sign VEU error",signingError);}
            }
        }

        // Receipt requests identify the transfer by TransactionID and deliberately
        // carry no HostID/PartnerID/UserID. Handle them before subscriber validation.
        if ("Receipt".equalsIgnoreCase(request.transactionPhase())) {
            if(reports.owns(request.transactionId()))return ResponseEntity.ok(reports.receipt(request));
            return ResponseEntity.ok(signed(responseBuilder.downloadReceipt(request.transactionId(), request)));
        }

        if (bankService.findBank(request.hostId()).isEmpty()) {
            throw new EbicsProtocolException("Unknown HostID: " + request.hostId());
        }

        if (request.partnerId() != null &&
                bankService.findCustomer(request.hostId(), request.partnerId()).isEmpty()) {
            throw new EbicsProtocolException(
                    "Unknown PartnerID: " + request.partnerId());
        }

        if (request.userId() != null &&
                bankService.findParticipant(request.hostId(), request.userId()).isEmpty()) {
            throw new EbicsProtocolException(
                    "Unknown UserID: " + request.userId());
        }

        System.out.printf(
                "EBICS request: version=%s host=%s partner=%s user=%s order=%s/%s%n",
                request.version(),
                request.hostId(),
                request.partnerId(),
                request.userId(),
                request.orderType(),
                request.orderId());

        if (downloadDenied(request)) {
            protocolLogService.rejected(request, "Download-Berechtigung fehlt");
            return ResponseEntity.ok(responseBuilder.error(EbicsErrorCode.EBICS_AUTHORISATION_ORDERTYPE_FAILED.code(), request));
        }

        verifyDownloadSignature(request);

        if ("H004".equals(request.version()) && request.orderType() != null) {
            String classicType = request.orderType().toUpperCase(java.util.Locale.ROOT);
            if (CLASSIC_DOWNLOAD_TYPES.contains(classicType)) {
                protocolLogService.accepted(request);
                return ResponseEntity.ok(classicDownload(request, classicType));
            }
            if (CLASSIC_UNSUPPORTED_TYPES.contains(classicType)) {
                protocolLogService.rejected(request, "Auftragsart nicht unterstützt: " + classicType);
                return ResponseEntity.ok(responseBuilder.error(EbicsErrorCode.EBICS_UNSUPPORTED_ORDER_TYPE.code(), request));
            }
        }

        if ("HAC".equalsIgnoreCase(request.orderType())) {
            hacService.acknowledge(request);
            return ResponseEntity.ok(reports.download(request,true));
        }
        if ("INI".equalsIgnoreCase(request.orderType()) || "HIA".equalsIgnoreCase(request.orderType()) || "HPB".equalsIgnoreCase(request.orderType())) {
            keyManagementService.process(request);
        }
        protocolLogService.accepted(request);
        if ("HPB".equalsIgnoreCase(request.orderType())) {
            var recipientEncryptionKey = keyManagementService.activeEncryptionKey(request.userId());
            return ResponseEntity.ok(responseBuilder.hpb(request, bankService.bank(request.hostId()),
                    bankKeyService.activeAuthenticationAndEncryptionKeys(), recipientEncryptionKey));
        }
        if ("INI".equalsIgnoreCase(request.orderType()) || "HIA".equalsIgnoreCase(request.orderType())) {
            return ResponseEntity.ok(responseBuilder.keyManagementSuccess(request));
        }
        if ("HPD".equalsIgnoreCase(request.orderType())) {
            return ResponseEntity.ok(responseBuilder.hpd(request, bankService.bank(request.hostId())));
        }
        if ("HKD".equalsIgnoreCase(request.orderType())) {
            return ResponseEntity.ok(responseBuilder.hkd(request, bankService.findParticipant(request.hostId(), request.userId())
                    .orElseThrow(() -> new EbicsProtocolException("HKD requires a known UserID"))));
        }
        if ("HTD".equalsIgnoreCase(request.orderType())) {
            return ResponseEntity.ok(htd(request));
        }
        if ("BTD".equalsIgnoreCase(request.orderType())) {
            if(request.btdMsgName()!=null&&request.btdMsgName().startsWith("pain.002"))return ResponseEntity.ok(reports.download(request,false));
            if(request.btdMsgName()!=null&&MT7XX_MSG_NAMES.contains(request.btdMsgName().toUpperCase(java.util.Locale.ROOT)))
                return ResponseEntity.ok(mt7xx(request, request.btdMsgName().toUpperCase(java.util.Locale.ROOT)));
            return ResponseEntity.ok(btd(request));
        }

        return ResponseEntity.ok(responseBuilder.success(request));
    }

    /**
     * Subsembly retrieves HTD immediately after HPB and only offers statement downloads when
     * the participant's accounts and the BTD/STA/camt.053 service are announced there.  A bare
     * generic success response is protocol-level successful but contains no usable subscription.
     */
    private String htd(EbicsRequest request) {
        var accounts = bankService.accounts().stream()
                .filter(Account::isActive)
                .filter(account -> request.partnerId().equals(account.getPartnerId()))
                .toList();
        if (accounts.isEmpty()) {
            throw new EbicsProtocolException("No active account found for PartnerID " + request.partnerId());
        }
        String accountInfo = accounts.stream().map(account -> """
                <AccountInfo ID="%s"><AccountNumber international="true">%s</AccountNumber><BankCode international="true">%s</BankCode><AccountHolder>%s</AccountHolder><Currency>%s</Currency></AccountInfo>
                """.formatted(xml(account.getIban()), xml(account.getIban()), xml(account.getBic()),
                xml(account.getAccountHolder()), xml(account.getCurrency()))).collect(java.util.stream.Collectors.joining());
        // Announce only what this participant may actually do - a client that
        // builds its menu from HTD then never offers an order the server would reject with 090003.
        var participant = bankService.findParticipant(request.hostId(), request.userId()).orElseThrow();
        boolean mayDownload = participant.isDownloadAllowed();
        boolean mayUpload = participant.isUploadAllowed();
        boolean mayDownloadLc = mayDownload && participant.isLcDownloadAllowed();
        String permissions = accounts.stream().map(account ->
                (mayDownload ? "<Permission><OrderTypes>BTD</OrderTypes><AccountID>%s</AccountID></Permission>\n".formatted(xml(account.getIban())) : "")
                + (mayUpload ? "<Permission><OrderTypes>BTU</OrderTypes><AccountID>%s</AccountID></Permission>\n".formatted(xml(account.getIban())) : ""))
                .collect(java.util.stream.Collectors.joining());
        String downloadOrderInfos = mayDownload ? """
                    <OrderInfo><AdminOrderType>BTD</AdminOrderType><Service><ServiceName>STA</ServiceName><MsgName>camt.053</MsgName></Service><Description>CAMT.053 Kontoauszüge</Description></OrderInfo>
                    <OrderInfo><AdminOrderType>BTD</AdminOrderType><Service><ServiceName>STM</ServiceName><MsgName>camt.052</MsgName></Service><Description>CAMT.052 untertägige Kontoauszüge</Description></OrderInfo>
                    <OrderInfo><AdminOrderType>BTD</AdminOrderType><Service><ServiceName>REP</ServiceName><MsgName>camt.054</MsgName></Service><Description>CAMT.054 Buchungsbenachrichtigungen</Description></OrderInfo>
                    <OrderInfo><AdminOrderType>BTD</AdminOrderType><Service><ServiceName>PSR</ServiceName><MsgName>pain.002</MsgName></Service><Description>pain.002 Zahlungsstatusberichte</Description></OrderInfo>
                """ : "";
        String uploadOrderInfos = mayUpload ? """
                    <OrderInfo><AdminOrderType>BTU</AdminOrderType><Service><ServiceName>SCT</ServiceName><MsgName>pain.001</MsgName></Service><Description>SEPA-Überweisungen</Description></OrderInfo>
                """ : "";
        // TRC ist kein realer EBICS-/ISO-20022-Service (MT7xx-Handelsfinanzierung laeuft in der
        // echten Welt ueber SWIFT FIN/SWIFTNet Trade Services) - eine bewusste, als solche
        // dokumentierte Erweiterung dieses Mock-Servers .
        String lcOrderInfos = mayDownloadLc ? """
                    <OrderInfo><AdminOrderType>BTD</AdminOrderType><Service><ServiceName>TRC</ServiceName><MsgName>MT700</MsgName></Service><Description>MT700 Dokumentenakkreditiv-Eröffnung (nicht EBICS-Standard)</Description></OrderInfo>
                    <OrderInfo><AdminOrderType>BTD</AdminOrderType><Service><ServiceName>TRC</ServiceName><MsgName>MT707</MsgName></Service><Description>MT707 Dokumentenakkreditiv-Änderung (nicht EBICS-Standard)</Description></OrderInfo>
                    <OrderInfo><AdminOrderType>BTD</AdminOrderType><Service><ServiceName>TRC</ServiceName><MsgName>MT710</MsgName></Service><Description>MT710 Drittbank-Avisierung (nicht EBICS-Standard)</Description></OrderInfo>
                    <OrderInfo><AdminOrderType>BTD</AdminOrderType><Service><ServiceName>TRC</ServiceName><MsgName>MT760</MsgName></Service><Description>MT760 Bankgarantie (nicht EBICS-Standard)</Description></OrderInfo>
                """ : "";
        String orderInfos = downloadOrderInfos + uploadOrderInfos + lcOrderInfos;
        String payload = """
                <HTDResponseOrderData xmlns="urn:org:ebics:H005">
                  <PartnerInfo>
                    <AddressInfo><Name>%s</Name></AddressInfo>
                    <BankInfo><HostID>%s</HostID></BankInfo>
                    %s
                    %s
                  </PartnerInfo>
                  <UserInfo><UserID Status="1">%s</UserID><Name>%s</Name>%s</UserInfo>
                </HTDResponseOrderData>
                """.formatted(xml(bankService.findCustomer(request.hostId(), request.partnerId()).orElseThrow().getName()),
                xml(request.hostId()), accountInfo, orderInfos, xml(request.userId()),
                xml(participant.getName()), permissions);
        var recipientEncryptionKey = keyManagementService.activeEncryptionKey(request.userId());
        String transactionId = HexFormat.of().formatHex(randomBytes(16)).toUpperCase();
        return signed(responseBuilder.downloadInitialization(request, payload.getBytes(StandardCharsets.UTF_8),
                recipientEncryptionKey, transactionId));
    }

    private static String xml(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }

    /**
     * EBICS 3.0 business-transaction-format download - the shape a real client sends for
     * camt.053 statement retrieval (AdminOrderType=BTD, Service/ServiceName=STA, MsgName=camt.053;
     * see EbicsRequestParser). Only the single-segment case is implemented (no multi-segment
     * Transfer phase): a full mandant's account statement comfortably fits in one segment, and
     * a typical client implementation only ever asks for one account's date range at a time.
     */
    private String btd(EbicsRequest request) {
        // Banking4 labels this as C53 but sends an H005 BTD request. Some versions place the
        // date range in StandardOrderParams rather than BTDOrderParams, so a missing parsed
        // service/date here means the subscribed default: STA/camt.053 for the recent period.
        // The simulator offers camt.053 statements, camt.054 notifications and pain.002
        // payment reports through BTD. Banking4 uses vendor-specific service identifiers for
        // C53, so unknown C53 aliases retain the CAMT.053 default.
        var partnerAccounts = activePartnerAccounts(request);
        String requestedPayload = request.xml().toLowerCase(java.util.Locale.ROOT);
        if (requestedPayload.contains("pain.002")) {
            return reports.download(request,false);
        }
        String format = requestedPayload.contains("camt.052") ? "camt.052"
                : requestedPayload.contains("camt.054") ? "camt.054" : "camt.053";
        return statementDownload(request, partnerAccounts, format);
    }

    /** EBICS 2.5 (H004) download by classic order type - same payload generators and
     * the same single-segment downloadInitialization as the BTD path, only the request shape differs. */
    private String classicDownload(EbicsRequest request, String orderType) {
        if ("Z01".equals(orderType)) {
            return reports.download(request, false);
        }
        String format = switch (orderType) {
            case "STA" -> "mt940";
            case "VMK" -> "mt942";
            case "C52" -> "camt.052";
            case "C54" -> "camt.054";
            case "Z52" -> "zip:camt.052";
            case "Z53" -> "zip:camt.053";
            case "Z54" -> "zip:camt.054";
            default -> "camt.053";
        };
        return statementDownload(request, activePartnerAccounts(request), format);
    }

    /** Z52/Z53/Z54: a ZIP container with one camt file per account, as German banks deliver them. */
    private static byte[] zipPerAccount(java.util.List<de.ostms.evils.ebics.domain.Statement> statements, String prefix,
                                        java.util.function.Function<de.ostms.evils.ebics.domain.Statement, String> generator) {
        try (var bytes = new java.io.ByteArrayOutputStream(); var zip = new java.util.zip.ZipOutputStream(bytes)) {
            for (var statement : statements) {
                zip.putNextEntry(new java.util.zip.ZipEntry(prefix + "_" + statement.iban() + "_" + statement.from() + "_" + statement.to() + ".xml"));
                zip.write(generator.apply(statement).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            zip.finish();
            return bytes.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("ZIP packaging failed", e);
        }
    }

    private java.util.List<Account> activePartnerAccounts(EbicsRequest request) {
        var partnerAccounts = bankService.accounts().stream()
                .filter(a -> a.getPartnerId().equals(request.partnerId()))
                .filter(Account::isActive)
                .toList();
        if (partnerAccounts.isEmpty()) {
            throw new EbicsProtocolException("No active account found for PartnerID " + request.partnerId());
        }
        return partnerAccounts;
    }

    private String statementDownload(EbicsRequest request, java.util.List<Account> partnerAccounts, String format) {
        LocalDate to = request.dateTo() == null ? LocalDate.now() : request.dateTo();
        // The MT942 interim report covers the current day by default, the statements 90 days.
        LocalDate from = request.dateFrom() == null ? ("mt942".equals(format) ? to : to.minusDays(90)) : request.dateFrom();
        var statements = partnerAccounts.stream()
                .map(account -> statementService.createStatement(account.getIban(), from, to))
                .toList();
        byte[] orderData = switch (format) {
            case "camt.052" -> camt052Generator.generateAll(statements).getBytes(StandardCharsets.UTF_8);
            case "camt.054" -> camt054Generator.generateAll(statements).getBytes(StandardCharsets.UTF_8);
            case "mt940" -> mt940Generator.generateAll(statements).getBytes(StandardCharsets.UTF_8);
            case "mt942" -> mt942Generator.generateAll(statements).getBytes(StandardCharsets.UTF_8);
            case "zip:camt.052" -> zipPerAccount(statements, "camt052", camt052Generator::generate);
            case "zip:camt.053" -> zipPerAccount(statements, "camt053", camt053Generator::generate);
            case "zip:camt.054" -> zipPerAccount(statements, "camt054", camt054Generator::generate);
            default -> camt053Generator.generateAll(statements).getBytes(StandardCharsets.UTF_8);
        };

        var recipientEncryptionKey = keyManagementService.activeEncryptionKey(request.userId());
        String transactionId = HexFormat.of().formatHex(randomBytes(16)).toUpperCase();
        // EBICS transport encryption already applies zlib compression. The client expects the
        // decrypted payload itself to be CAMT XML (or the MT940 text); an additional ZIP container
        // would be saved as a .xml file and fail XML parsing - so only the Z52/Z53/Z54 order types,
        // which are ZIP by definition, deliver one.
        return signed(responseBuilder.downloadInitialization(request, orderData,
                recipientEncryptionKey, transactionId));
    }

    /**
     * BTD-Abruf eines zuvor ueber das Admin-UI generierten MT700-/MT707-/MT710-/MT760-Dokuments
     * (siehe LcDocumentService) - KEIN realer EBICS-Standard (siehe MT7XX_MSG_NAMES-
     * Kommentar), aber vom selben downloadInitialization-Pfad wie camt.053/pain.002 getragen, damit
     * ein EBICS-Client es wie jeden anderen BTD-Abruf behandeln kann. Nur Single-Segment, wie btd().
     */
    private String mt7xx(EbicsRequest request, String msgName) {
        String orderData = lcDocumentService.abrufen(request.hostId(), request.partnerId(), msgName);
        var recipientEncryptionKey = keyManagementService.activeEncryptionKey(request.userId());
        String transactionId = HexFormat.of().formatHex(randomBytes(16)).toUpperCase();
        return signed(responseBuilder.downloadInitialization(request, orderData.getBytes(StandardCharsets.UTF_8),
                recipientEncryptionKey, transactionId));
    }

    private byte[] zip(String xml) {
        try {var output=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(output)){zip.putNextEntry(new ZipEntry("statement-1.xml"));zip.write(xml.getBytes(StandardCharsets.UTF_8));zip.closeEntry();}return output.toByteArray();}
        catch(Exception e){throw new EbicsProtocolException("Could not package statement",e);}
    }

    private String signed(String response) {
        try{return EbicsAuthentication.sign(response,bankKeyService.authenticationPrivateKey());}
        catch(Exception e){throw new EbicsProtocolException("Could not sign EBICS response",e);}
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }
}
