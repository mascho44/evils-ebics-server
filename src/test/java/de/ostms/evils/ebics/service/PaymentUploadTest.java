package de.ostms.evils.ebics.service;

import de.ostms.evils.ebics.client.*;
import de.ostms.evils.ebics.domain.*;
import de.ostms.evils.ebics.persistence.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PaymentUploadTest {
    static final String IBAN="DE89370400440532013000";
    static final String INTERNAL_IBAN="DE02120300000000202051";
    @Autowired PaymentUploadService uploads;
    @Autowired ParticipantRepository participants;
    @Autowired ParticipantKeyRepository keys;
    @Autowired AccountRepository accounts;
    @Autowired PaymentAuthorizationService authorizations;
    @Autowired PaymentOrderRepository orders;
    @Autowired TransactionRepository ledger;
    @Autowired BankKeyService bankKeys;
    @Autowired PaymentReportService reports;
    @Autowired VeuService veu;
    @Autowired PaymentOrderService paymentService;
    @Autowired StatementService statements;
    @Autowired StatusSchemaValidator statusValidator;
    @Autowired de.ostms.evils.ebics.protocol.EbicsController controller;
    @Autowired CustomerRepository customers;
    EbicsKeyMaterial material;
    EbicsClient.BankKeys bank;
    String user;

    @BeforeAll void setup()throws Exception {
        material=EbicsKeyMaterial.generate();
        user="PAYTEST";
        var participant=new Participant("EVILSBANK",user,"PAYPARTNER","Payment tester",true,"INITIALIZED","INITIALIZED","INITIALIZED");participant.releaseSignatureKey();participants.save(participant);
        accounts.save(new Account("EVILSBANK",IBAN,"COBADEFFXXX","PAYPARTNER","Tester",new BigDecimal("1000.00"),LocalDate.now().minusDays(1),"EUR",true));
        store("AUTHENTICATION","X002",material.authentication().getPublic());
        store("SIGNATURE","A006",material.signature().getPublic());
        store("ENCRYPTION","E002",material.encryption().getPublic());
        customers.save(new Customer("EVILSBANK","PAYPARTNER","Payment tests",true));
        bankKeys.generateActiveKeySet();
        var active=bankKeys.activeAuthenticationAndEncryptionKeys();
        bank=new EbicsClient.BankKeys((RSAPublicKey)HpbCodec.decodeKey(active.stream().filter(k->k.getKeyType().equals("AUTHENTICATION")).findFirst().orElseThrow().getPublicKeyBase64()),(RSAPublicKey)HpbCodec.decodeKey(active.stream().filter(k->k.getKeyType().equals("ENCRYPTION")).findFirst().orElseThrow().getPublicKeyBase64()));
    }
    @BeforeEach void permissions(){authorizations.set(user,null,"E");}
    void store(String type,String version,PublicKey key){keys.save(new ParticipantKey(user,type,version,"RSA-3072",Base64.getEncoder().encodeToString(key.getEncoded()),"test"));}
    byte[] pain(String id,String amount,LocalDate day){return painTo(id,amount,day,"DE12500105170648489890");}
    byte[] painTo(String id,String amount,LocalDate day,String creditorIban){return new SepaPayment("Tester",IBAN,"Recipient",creditorIban,new BigDecimal(amount),"Local isolated test",day,"END-"+id).toXml(id,OffsetDateTime.parse("2026-01-01T12:00:00Z")).getBytes(StandardCharsets.UTF_8);}
    String request(byte[] pain,PrivateKey signing)throws Exception {
        String transfer=HpbCodec.encrypt(PaymentWire.wrap(pain,signing).getBytes(StandardCharsets.UTF_8),bank.encryption()).replace("<DataTransfer>","<DataTransfer authenticate=\"true\">");
        return EbicsAuthentication.sign("<ebicsRequest xmlns=\"urn:org:ebics:H005\" Version=\"H005\"><header authenticate=\"true\"><static><HostID>EVILSBANK</HostID><PartnerID>PAYPARTNER</PartnerID><UserID>"+user+"</UserID><Timestamp>"+Instant.now()+"</Timestamp><OrderDetails><AdminOrderType>BTU</AdminOrderType><Profile>EVILS-SCT-1</Profile></OrderDetails></static><mutable><TransactionPhase>Initialisation</TransactionPhase></mutable></header><AuthSignature/><body>"+transfer+"</body></ebicsRequest>",material.authentication().getPrivate());
    }
    String standardRequest(byte[] pain)throws Exception {
        return EbicsAuthentication.sign("<ebicsRequest xmlns=\"urn:org:ebics:H005\" Version=\"H005\"><header authenticate=\"true\"><static><HostID>EVILSBANK</HostID><PartnerID>PAYPARTNER</PartnerID><UserID>"+user+"</UserID><Timestamp>"+Instant.now()+"</Timestamp><OrderDetails><AdminOrderType>BTU</AdminOrderType><BTUOrderParams><Service><ServiceName>SCT</ServiceName><MsgName>pain.001</MsgName></Service></BTUOrderParams></OrderDetails></static><mutable><TransactionPhase>Initialisation</TransactionPhase></mutable></header><AuthSignature/><body/></ebicsRequest>",material.authentication().getPrivate());
    }
    String standardTransfer(String transactionId,byte[] pain)throws Exception {
        String transfer=HpbCodec.encrypt(pain,bank.encryption());
        return EbicsAuthentication.sign("<ebicsRequest xmlns=\"urn:org:ebics:H005\" Version=\"H005\"><header authenticate=\"true\"><static><TransactionID>"+transactionId+"</TransactionID></static><mutable><TransactionPhase>Transfer</TransactionPhase><SegmentNumber lastSegment=\"true\">1</SegmentNumber></mutable></header><AuthSignature/><body>"+transfer+"</body></ebicsRequest>",material.authentication().getPrivate());
    }
    /** ebics-java-client (and other clients still built against the classic per-type OrderType
     * enum, not the newer BTU/Service model) sends the legacy "FUL" AdminOrderType instead of
     * "BTU" for a payment-file upload - the simulator must still recognize it as an upload
     * (see EbicsController.receive()/PaymentUploadService.initialise()), otherwise the client
     * gets a bare success response with no TransactionID and can never build a valid
     * Transfer-phase request (this exact bug shipped and broke a real client, see PR history). */
    String legacyFulRequest(byte[] pain)throws Exception {
        return EbicsAuthentication.sign("<ebicsRequest xmlns=\"urn:org:ebics:H005\" Version=\"H005\"><header authenticate=\"true\"><static><HostID>EVILSBANK</HostID><PartnerID>PAYPARTNER</PartnerID><UserID>"+user+"</UserID><Timestamp>"+Instant.now()+"</Timestamp><OrderDetails><AdminOrderType>FUL</AdminOrderType><BTUOrderParams><Service><ServiceName>SCT</ServiceName><MsgName>pain.001</MsgName></Service></BTUOrderParams></OrderDetails></static><mutable><TransactionPhase>Initialisation</TransactionPhase></mutable></header><AuthSignature/><body/></ebicsRequest>",material.authentication().getPrivate());
    }
    @Test void legacyFulAdminOrderTypeIsAcceptedLikeBtuAndReturnsATransactionId()throws Exception {
        long before=ledger.count(), orderCount=orders.count();
        String initial=uploads.receive(legacyFulRequest(pain("LEGACY-FUL","9.99",LocalDate.now())));
        de.ostms.evils.ebics.protocol.SchemaAssertions.response(initial);
        String transactionId=HpbCodec.text(HpbCodec.parse(initial.getBytes(StandardCharsets.UTF_8)).getDocumentElement(),"TransactionID");
        assertThat(transactionId).isNotBlank();
        String response=uploads.receive(standardTransfer(transactionId,pain("LEGACY-FUL","9.99",LocalDate.now())));
        de.ostms.evils.ebics.protocol.SchemaAssertions.response(response);
        assertThat(response).contains("000000").contains("gebucht");
        assertThat(ledger.count()).isEqualTo(before+1);assertThat(orders.count()).isEqualTo(orderCount+1);
    }
    @Test void standardBtuSctOrderDataIsAcceptedWithoutTheInternalEnvelope()throws Exception {
        long before=ledger.count(), orderCount=orders.count();
        String initial=uploads.receive(standardRequest(pain("STANDARD","9.99",LocalDate.now())));
        String transactionId=HpbCodec.text(HpbCodec.parse(initial.getBytes(StandardCharsets.UTF_8)).getDocumentElement(),"TransactionID");
        String response=uploads.receive(standardTransfer(transactionId,pain("STANDARD","9.99",LocalDate.now())));
        de.ostms.evils.ebics.protocol.SchemaAssertions.response(initial);
        de.ostms.evils.ebics.protocol.SchemaAssertions.response(response);
        assertThat(response).contains("000000").contains("gebucht");
        assertThat(ledger.count()).isEqualTo(before+1);assertThat(orders.count()).isEqualTo(orderCount+1);
    }
    @Test void booksMatchingCreditForInternalEurRecipient()throws Exception {
        accounts.save(new Account("EVILSBANK",INTERNAL_IBAN,"INGDDEFFXXX","PAYPARTNER","Recipient",BigDecimal.ZERO,LocalDate.now(),"EUR",true));
        long before=ledger.count();
        uploads.receive(request(painTo("INTERNAL","9.99",LocalDate.now(),INTERNAL_IBAN),material.signature().getPrivate()));
        PaymentOrder order=orders.findAll().stream().filter(o->"END-INTERNAL".equals(o.getEndToEndId())).findFirst().orElseThrow();
        Transaction credit=ledger.findById("PAY-"+order.getId()+"-CREDIT").orElseThrow();
        assertThat(ledger.count()).isEqualTo(before+2);
        assertThat(credit.getIban()).isEqualTo(INTERNAL_IBAN);
        assertThat(credit.isCredit()).isTrue();
        assertThat(credit.getAmount()).isEqualByComparingTo("9.99");
    }
    @Test void realClientBooksOnceAndVerifiesSignedReply()throws Exception {
        var client=new EbicsClient(new EbicsClientConfig(URI.create("https://test.invalid"),"EVILSBANK","PAYPARTNER",user),material,xml->{try{return uploads.receive(xml);}catch(Exception e){throw new java.io.IOException(e);}});
        client.trustBankKeys(bank);
        byte[] pain=pain("ROUNDTRIP","12.34",LocalDate.now());
        long before=ledger.count(),orderCount=orders.count();
        assertThat(client.submitPayment(pain)).contains("gebucht");
        assertThat(client.submitPayment(pain)).contains("Bereits gebucht");
        assertThat(ledger.count()).isEqualTo(before+1);
        assertThat(orders.count()).isEqualTo(orderCount+1);
        assertThat(orders.findAll().stream().filter(o->"END-ROUNDTRIP".equals(o.getEndToEndId())).findFirst().orElseThrow().getStatus()).isEqualTo(PaymentOrderStatus.APPROVED);
    }
    @Test void changedMessageIdPayloadDoesNotBookAgain()throws Exception {
        uploads.receive(request(pain("CONFLICT","1.00",LocalDate.now()),material.signature().getPrivate()));
        long before=ledger.count();
        assertThatThrownBy(()->uploads.receive(request(pain("CONFLICT","2.00",LocalDate.now()),material.signature().getPrivate()))).isInstanceOf(SecurityException.class);
        assertThat(ledger.count()).isEqualTo(before);
    }
    @Test void badOrderSignatureAndTamperedTransportDoNotBook()throws Exception {
        long before=ledger.count(),orderCount=orders.count();
        byte[] pain=pain("TAMPER","1.00",LocalDate.now());
        assertThatThrownBy(()->uploads.receive(request(pain,material.authentication().getPrivate()))).isInstanceOf(SecurityException.class);
        String valid=request(pain,material.signature().getPrivate());
        assertThatThrownBy(()->uploads.receive(valid.replace("Initialisation","Transfer"))).isInstanceOf(Exception.class);
        assertThat(ledger.count()).isEqualTo(before);assertThat(orders.count()).isEqualTo(orderCount);
    }
    @Test void technicalPermissionCannotSign()throws Exception {
        authorizations.set(user,null,"T");long before=ledger.count();
        assertThatThrownBy(()->uploads.receive(request(pain("TECHNICAL","1.00",LocalDate.now()),material.signature().getPrivate()))).isInstanceOf(SecurityException.class);
        assertThat(ledger.count()).isEqualTo(before);
    }
    @Test void perAccountSignatureClassOverridesTechnicalDefault()throws Exception {
        authorizations.set(user,null,"T");authorizations.set(user,IBAN,"E");
        long before=orders.count();
        try {
            uploads.receive(request(pain("PERACCOUNT","1.00",LocalDate.now()),material.signature().getPrivate()));
            assertThat(orders.count()).isEqualTo(before+1);
        } finally {authorizations.removeOverride(user,IBAN);}
    }
    @Test void technicalDefaultStillRejectsAccountsWithoutOverride()throws Exception {
        authorizations.set(user,null,"E");authorizations.set(user,IBAN,"T");
        long before=orders.count();
        try {
            assertThatThrownBy(()->uploads.receive(request(pain("TECHACCOUNT","1.00",LocalDate.now()),material.signature().getPrivate()))).isInstanceOf(SecurityException.class);
            assertThat(orders.count()).isEqualTo(before);
        } finally {authorizations.removeOverride(user,IBAN);}
    }
    @Test void futureDatedPaymentsAreScheduledAndForeignAccountsCannotBook()throws Exception {
        long before=ledger.count(),orderCount=orders.count();
        uploads.receive(request(pain("FUTURE","1.00",LocalDate.now().plusDays(1)),material.signature().getPrivate()));
        assertThat(orders.findAll().stream().filter(o->"FUTURE".equals(o.getOriginalMessageId())).findFirst().orElseThrow().getStatus()).isEqualTo(PaymentOrderStatus.SCHEDULED);
        byte[] foreign=new String(pain("FOREIGN","1.00",LocalDate.now()),StandardCharsets.UTF_8).replace(IBAN,"DE75512108001245126199").getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(()->uploads.receive(request(foreign,material.signature().getPrivate()))).isInstanceOf(SecurityException.class);
        assertThat(ledger.count()).isEqualTo(before);assertThat(orders.count()).isEqualTo(orderCount+1);
    }
    @Test void concurrentRetriesBookOnce()throws Exception {
        String xml=request(pain("CONCURRENT","1.00",LocalDate.now()),material.signature().getPrivate());
        long before=ledger.count();
        try(var executor=Executors.newFixedThreadPool(2)){
            var start=new CountDownLatch(1);
            Callable<String> task=()->{start.await();return uploads.receive(xml);};
            var a=executor.submit(task);var b=executor.submit(task);start.countDown();
            assertThat(a.get(30,TimeUnit.SECONDS)).contains("000000");assertThat(b.get(30,TimeUnit.SECONDS)).contains("000000");
        }
        assertThat(ledger.count()).isEqualTo(before+1);
    }
    @Test void unreleasedSignatureKeyCannotBook()throws Exception {
        var participant=new Participant("EVILSBANK",user,"PAYPARTNER","Payment tester",true,"INITIALIZED","INITIALIZED","INITIALIZED");participants.save(participant);
        long before=ledger.count();
        try {assertThatThrownBy(()->uploads.receive(request(pain("UNRELEASED","1.00",LocalDate.now()),material.signature().getPrivate()))).isInstanceOf(SecurityException.class);assertThat(ledger.count()).isEqualTo(before);}
        finally {participant.releaseSignatureKey();participants.save(participant);}
    }
    @Test void clientRejectsUnrelatedSignedSuccessAndUnsignedSuccess()throws Exception {
        String old=uploads.receive(request(pain("OLDREPLY","1.00",LocalDate.now()),material.signature().getPrivate()));
        for(String reply:List.of(old,"<ebicsResponse><ReturnCode>000000</ReturnCode></ebicsResponse>")){
            var client=new EbicsClient(new EbicsClientConfig(URI.create("https://test.invalid"),"EVILSBANK","PAYPARTNER",user),material,xml->reply);client.trustBankKeys(bank);
            assertThatThrownBy(()->client.submitPayment(pain("NEWREPLY","2.00",LocalDate.now()))).isInstanceOf(Exception.class);
        }
    }
    String reportRequest(String order,String partner)throws Exception{
        String details=order.equals("HAC")?"<AdminOrderType>HAC</AdminOrderType><StandardOrderParams><DateRange><Start>"+LocalDate.now()+"</Start><End>"+LocalDate.now()+"</End></DateRange></StandardOrderParams>":"<AdminOrderType>BTD</AdminOrderType><BTDOrderParams><Service><ServiceName>REP</ServiceName><MsgName>pain.002</MsgName></Service></BTDOrderParams>";
        if(order.equals("C53"))details=details.replace("pain.002","camt.053");
        return EbicsAuthentication.sign("<ebicsRequest xmlns=\"urn:org:ebics:H005\" Version=\"H005\"><header authenticate=\"true\"><static><HostID>EVILSBANK</HostID><PartnerID>"+partner+"</PartnerID><UserID>"+user+"</UserID><OrderDetails>"+details+"</OrderDetails></static><mutable><TransactionPhase>Initialisation</TransactionPhase></mutable></header><AuthSignature/><body/></ebicsRequest>",material.authentication().getPrivate());
    }
    String receipt(String tx,int code)throws Exception{
        return EbicsAuthentication.sign("<ebicsRequest xmlns=\"urn:org:ebics:H005\" Version=\"H005\"><header authenticate=\"true\"><static><TransactionID>"+tx+"</TransactionID></static><mutable><TransactionPhase>Receipt</TransactionPhase></mutable></header><AuthSignature/><body><TransferReceipt authenticate=\"true\"><ReceiptCode>"+code+"</ReceiptCode></TransferReceipt></body></ebicsRequest>",material.authentication().getPrivate());
    }
    String veuRequest(String type,String params,String body)throws Exception {
        return EbicsAuthentication.sign("<ebicsRequest xmlns=\"urn:org:ebics:H005\" Version=\"H005\"><header authenticate=\"true\"><static><HostID>EVILSBANK</HostID><PartnerID>PAYPARTNER</PartnerID><UserID>"+user+"</UserID><Timestamp>"+Instant.now()+"</Timestamp><OrderDetails><AdminOrderType>"+type+"</AdminOrderType>"+params+"</OrderDetails></static><mutable><TransactionPhase>Initialisation</TransactionPhase></mutable></header><AuthSignature/><body>"+body+"</body></ebicsRequest>",material.authentication().getPrivate());
    }
    String veuParams(String type,String orderId,String flags){return "<"+type+"OrderParams><PartnerID>PAYPARTNER</PartnerID><Service><ServiceName>SCT</ServiceName><Scope>DE</Scope><MsgName version=\"09\" variant=\"001\" format=\"XML\">pain.001</MsgName></Service><OrderID>"+orderId+"</OrderID>"+flags+"</"+type+"OrderParams>";}
    String veuSignature(byte[] payload,PrivateKey signing)throws Exception {
        String sig="<UserSignatureData xmlns=\"http://www.ebics.org/S002\"><OrderSignatureData><SignatureVersion>A006</SignatureVersion><SignatureValue>"+Base64.getEncoder().encodeToString(A006Signature.sign(payload,signing))+"</SignatureValue><PartnerID>PAYPARTNER</PartnerID><UserID>"+user+"</UserID></OrderSignatureData></UserSignatureData>";
        return HpbCodec.encrypt(sig.getBytes(StandardCharsets.UTF_8),bank.encryption()).replace("<OrderData>","<SignatureData authenticate=\"true\">").replace("</OrderData>","</SignatureData><DataDigest SignatureVersion=\"A006\">"+Base64.getEncoder().encodeToString(A006Signature.digest(payload))+"</DataDigest>");
    }
    byte[] veuClear(String response)throws Exception {
        de.ostms.evils.ebics.protocol.SchemaAssertions.response(response);
        var doc=HpbCodec.parse(response.getBytes(StandardCharsets.UTF_8));EbicsAuthentication.verify(doc,bank.authentication());
        return PaymentWire.decrypt(doc.getDocumentElement(),material.encryption().getPrivate());
    }
    @Test void veuListsDetailsAndVerifiesSignatureBeforeBookingExactlyOnce()throws Exception {
        var draft=paymentService.create(IBAN,new BigDecimal("10.00"),"VEU recipient","DE12500105170648489890",null,"VEU regression","VEU-TEST",LocalDate.now());
        var published=veu.publish(draft.getId(),user);
        assertThat(veu.publish(draft.getId(),user).getOrderId()).isEqualTo(published.getOrderId());
        assertThat(paymentService.signatures(draft.getId())).isEmpty();
        for(String type:List.of("HVU","HVZ","HVD","HVT")){
            String params=Set.of("HVU","HVZ").contains(type)?"":veuParams(type,published.getOrderId(),type.equals("HVT")?"<OrderFlags completeOrderData=\"false\" fetchLimit=\"100\" fetchOffset=\"0\"/>":"");
            String xml=new String(veuClear(controller.receive(veuRequest(type,params,"")).getBody()),StandardCharsets.UTF_8);
            de.ostms.evils.ebics.protocol.SchemaAssertions.veu(xml);
            assertThat(xml).contains(type+"ResponseOrderData");
        }
        byte[] original=veuClear(controller.receive(veuRequest("HVT",veuParams("HVT",published.getOrderId(),"<OrderFlags completeOrderData=\"true\" fetchLimit=\"0\" fetchOffset=\"0\"/>"),"")).getBody());
        assertThat(original).isEqualTo(published.getPaymentXml().getBytes(StandardCharsets.UTF_8));
        var before=ledger.count();
        String bad=controller.receive(veuRequest("HVE",veuParams("HVE",published.getOrderId(),""),veuSignature("different payment".getBytes(StandardCharsets.UTF_8),material.signature().getPrivate()))).getBody();
        assertThat(bad).contains("091116");assertThat(ledger.count()).isEqualTo(before);
        assertThat(paymentService.signatures(draft.getId())).isEmpty();
        String wrongKey=controller.receive(veuRequest("HVE",veuParams("HVE",published.getOrderId(),""),veuSignature(original,material.authentication().getPrivate()))).getBody();
        assertThat(wrongKey).contains("091116");assertThat(ledger.count()).isEqualTo(before);
        assertThat(paymentService.signatures(draft.getId())).isEmpty();
        String signed=veuRequest("HVE",veuParams("HVE",published.getOrderId(),""),veuSignature(original,material.signature().getPrivate()));
        for(int i=0;i<2;i++){String response=controller.receive(signed).getBody();assertThat(response).contains("000000").doesNotContain("091116");de.ostms.evils.ebics.protocol.SchemaAssertions.response(response);}
        assertThat(ledger.count()).isEqualTo(before+1);
        assertThat(paymentService.signatures(draft.getId())).hasSize(1);
        assertThat(orders.findById(draft.getId()).orElseThrow().getStatus()).isEqualTo(PaymentOrderStatus.APPROVED);
    }
    @Test void veuRejectsTechnicalUserAndDifferentOrderSignature()throws Exception {
        var one=paymentService.create(IBAN,new BigDecimal("11.00"),"Recipient","DE12500105170648489890",null,"VEU negative","VEU-NEG",LocalDate.now());
        var two=paymentService.create(IBAN,new BigDecimal("12.00"),"Recipient","DE12500105170648489890",null,"VEU other","VEU-OTHER",LocalDate.now());
        var v1=veu.publish(one.getId(),user);var v2=veu.publish(two.getId(),user);
        var count=ledger.count();
        String wrong=controller.receive(veuRequest("HVE",veuParams("HVE",v2.getOrderId(),""),veuSignature(v1.getPaymentXml().getBytes(StandardCharsets.UTF_8),material.signature().getPrivate()))).getBody();
        assertThat(wrong).contains("091116");assertThat(ledger.count()).isEqualTo(count);
        authorizations.set(user,null,"T");
        try {assertThat(controller.receive(veuRequest("HVZ","","")).getBody()).contains("091116");}
        finally {authorizations.set(user,null,"E");}
    }
    @Test void veuRequiresTwoDifferentSignersForAAndB()throws Exception {
        String originalUser=user;
        var draft=paymentService.create(IBAN,new BigDecimal("13.00"),"Recipient","DE12500105170648489890",null,"VEU two signers","VEU-AB",LocalDate.now());
        var v=veu.publish(draft.getId(),user);byte[] data=v.getPaymentXml().getBytes(StandardCharsets.UTF_8);
        long before=ledger.count();authorizations.set(user,null,"A");
        try {
            assertThat(controller.receive(veuRequest("HVE",veuParams("HVE",v.getOrderId(),""),veuSignature(data,material.signature().getPrivate()))).getBody()).contains("000000");
            assertThat(ledger.count()).isEqualTo(before);
            assertThat(orders.findById(draft.getId()).orElseThrow().getStatus()).isEqualTo(PaymentOrderStatus.PENDING_SIGNATURES);
            user="VEU-SECOND";
            var second=new Participant("EVILSBANK",user,"PAYPARTNER","Second signer",true,"INITIALIZED","INITIALIZED","INITIALIZED");second.releaseSignatureKey();participants.save(second);
            store("AUTHENTICATION","X002",material.authentication().getPublic());store("SIGNATURE","A006",material.signature().getPublic());store("ENCRYPTION","E002",material.encryption().getPublic());
            authorizations.set(user,null,"B");
            String summary=new String(veuClear(controller.receive(veuRequest("HVZ","","")).getBody()),StandardCharsets.UTF_8);
            de.ostms.evils.ebics.protocol.SchemaAssertions.veu(summary);assertThat(summary).contains("SignerInfo");
            assertThat(controller.receive(veuRequest("HVE",veuParams("HVE",v.getOrderId(),""),veuSignature(data,material.signature().getPrivate()))).getBody()).contains("000000");
            assertThat(ledger.count()).isEqualTo(before+1);
        }finally{user=originalUser;authorizations.set(user,null,"E");}
    }
    @Test void emptyReportsReturnSignedNoDataInsteadOfParticipantError()throws Exception {
        for(String kind:List.of("HAC","PSR")) {
            String request=reportRequest(kind,"PAYPARTNER");
            // Use a past range that cannot contain events from this test run.
            var parsed=HpbCodec.parse(request.getBytes(StandardCharsets.UTF_8));
            var details=(org.w3c.dom.Element)parsed.getElementsByTagNameNS("urn:org:ebics:H005","OrderDetails").item(0);
            var existing=details.getElementsByTagNameNS("urn:org:ebics:H005","StandardOrderParams");
            if(existing.getLength()>0)details.removeChild(existing.item(0));
            var params=parsed.createElementNS("urn:org:ebics:H005","StandardOrderParams");
            var range=parsed.createElementNS("urn:org:ebics:H005","DateRange");
            for(String name:List.of("Start","End")){var date=parsed.createElementNS("urn:org:ebics:H005",name);date.setTextContent("2000-01-01");range.appendChild(date);}
            params.appendChild(range);details.appendChild(params);
            var auth=parsed.getElementsByTagNameNS("urn:org:ebics:H005","AuthSignature").item(0);
            while(auth.hasChildNodes())auth.removeChild(auth.getFirstChild());
            request=EbicsAuthentication.sign(EbicsAuthentication.serialize(parsed),material.authentication().getPrivate());
            String response=controller.receive(request).getBody();
            assertThat(response).contains("090005","EBICS_NO_DOWNLOAD_DATA_AVAILABLE").doesNotContain("091002");
            EbicsAuthentication.verify(HpbCodec.parse(response.getBytes(StandardCharsets.UTF_8)),bank.authentication());
            de.ostms.evils.ebics.protocol.SchemaAssertions.response(response);
        }
    }
    List<String> decryptReports(String response)throws Exception{
        var doc=HpbCodec.parse(response.getBytes(StandardCharsets.UTF_8));EbicsAuthentication.verify(doc,bank.authentication());
        if(response.contains("urn:org:ebics:H004"))de.ostms.evils.ebics.protocol.SchemaAssertions.responseH004(response);
        else de.ostms.evils.ebics.protocol.SchemaAssertions.response(response);
        byte[] clear=PaymentWire.decrypt(doc.getDocumentElement(),material.encryption().getPrivate());
        List<String> files=new ArrayList<>();
        if(clear[0]=='P'&&clear[1]=='K'){try(var zip=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(clear))){while(zip.getNextEntry()!=null)files.add(new String(zip.readAllBytes(),StandardCharsets.UTF_8));}}
        else files.add(new String(clear,StandardCharsets.UTF_8));
        files.forEach(statusValidator::validate);return files;
    }
    @Test void uploadLedgerStatementsHacAndPain002RoundTrip()throws Exception{
        accounts.save(new Account("EVILSBANK",INTERNAL_IBAN,"INGDDEFFXXX","PAYPARTNER","Recipient",BigDecimal.ZERO,LocalDate.now(),"EUR",true));
        byte[] payload=new String(painTo("REPORT-E2E","123.00",LocalDate.now(),INTERNAL_IBAN),StandardCharsets.UTF_8)
            .replace("<PmtInfId>REPORT-E2E</PmtInfId>","<PmtInfId>PAYMENT-GROUP</PmtInfId>")
            .replace("<PmtId>","<PmtId><InstrId>INSTRUCTION-123</InstrId>").getBytes(StandardCharsets.UTF_8);
        var beforeDebit=statements.createStatement(IBAN,LocalDate.now(),LocalDate.now()).closingBalance();
        var beforeCredit=statements.createStatement(INTERNAL_IBAN,LocalDate.now(),LocalDate.now()).closingBalance();
        String initial=uploads.receive(standardRequest(payload));
        String tx=HpbCodec.text(HpbCodec.parse(initial.getBytes(StandardCharsets.UTF_8)).getDocumentElement(),"TransactionID");
        String response=uploads.receive(standardTransfer(tx,payload));
        var order=orders.findAll().stream().filter(o->"REPORT-E2E".equals(o.getOriginalMessageId())).findFirst().orElseThrow();
        assertThat(response).contains("<OrderID>"+order.getEbicsOrderId()+"</OrderID>");
        assertThat(order.getEbicsOrderId()).matches("[A-Z][A-Z0-9]{3}");
        assertThat(statements.createStatement(IBAN,LocalDate.now(),LocalDate.now()).closingBalance()).isEqualByComparingTo(beforeDebit.subtract(new BigDecimal("123")));
        assertThat(statements.createStatement(INTERNAL_IBAN,LocalDate.now(),LocalDate.now()).closingBalance()).isEqualByComparingTo(beforeCredit.add(new BigDecimal("123")));
        var camt=new de.ostms.evils.ebics.camt.Camt053Generator();
        de.ostms.evils.ebics.protocol.SchemaAssertions.camt(camt.generate(statements.createStatement(IBAN,LocalDate.now(),LocalDate.now())));
        de.ostms.evils.ebics.protocol.SchemaAssertions.camt(camt.generate(statements.createStatement(INTERNAL_IBAN,LocalDate.now(),LocalDate.now())));
        String statementResponse=controller.receive(reportRequest("C53","PAYPARTNER")).getBody();
        var statementDocument=HpbCodec.parse(statementResponse.getBytes(StandardCharsets.UTF_8));
        EbicsAuthentication.verify(statementDocument,bank.authentication());
        String statementXml=new String(PaymentWire.decrypt(statementDocument.getDocumentElement(),material.encryption().getPrivate()),StandardCharsets.UTF_8);
        de.ostms.evils.ebics.protocol.SchemaAssertions.camt(statementXml);
        assertThat(statementXml).contains("END-REPORT-E2E","<IBAN>"+INTERNAL_IBAN+"</IBAN>");
        String psr=controller.receive(reportRequest("PSR","PAYPARTNER")).getBody();
        assertThat(decryptReports(psr)).anySatisfy(file->assertThat(file).contains("<OrgnlMsgId>REPORT-E2E</OrgnlMsgId>","<OrgnlMsgNmId>pain.001.001.09</OrgnlMsgNmId>","<OrgnlPmtInfId>PAYMENT-GROUP</OrgnlPmtInfId>","<OrgnlInstrId>INSTRUCTION-123</OrgnlInstrId>","<TxSts>ACSC</TxSts>"));
        assertThat(orders.findById(order.getId()).orElseThrow().getStatusReportRetrievedAt()).isNull();
        String downloadId=HpbCodec.text(HpbCodec.parse(psr.getBytes(StandardCharsets.UTF_8)).getDocumentElement(),"TransactionID");
        String ack=controller.receive(receipt(downloadId,0)).getBody();de.ostms.evils.ebics.protocol.SchemaAssertions.response(ack);
        assertThat(ack).contains("011000");
        assertThat(orders.findById(order.getId()).orElseThrow().getStatusReportRetrievedAt()).isNotNull();
        assertThat(controller.receive(receipt(downloadId,0)).getBody()).contains("011000");
        String hac=controller.receive(reportRequest("HAC","PAYPARTNER")).getBody();
        assertThat(decryptReports(hac).getFirst()).contains("FILE_UPLOAD","ORDER_HAC_FINAL_POS","<Id>"+order.getEbicsOrderId()+"</Id>","<Prtry>OrderID</Prtry>","<Cd>TS01</Cd>");
        assertThat(paymentService.detail(order.getId()).events()).anyMatch(e->e.getKind().equals("REPORT_RECEIVED"));
    }
    @Test void unsignedAndForeignPartnerReportRequestsAreRejected()throws Exception{
        var parser=new de.ostms.evils.ebics.protocol.EbicsRequestParser();
        assertThatThrownBy(()->reports.download(parser.parse(reportRequest("HAC","OTHER")),true)).isInstanceOf(de.ostms.evils.ebics.protocol.EbicsProtocolException.class);
        String tampered=reportRequest("HAC","PAYPARTNER").replace("PAYPARTNER","OTHER");
        assertThatThrownBy(()->reports.download(parser.parse(tampered),true)).isInstanceOf(de.ostms.evils.ebics.protocol.EbicsProtocolException.class);
    }
    @Test void batchIsAtomicAndIdempotent()throws Exception{
        String single=new String(pain("BATCH","4.00",LocalDate.now()),StandardCharsets.UTF_8);
        String transfer=single.substring(single.indexOf("<CdtTrfTxInf>"),single.indexOf("</CdtTrfTxInf>")+"</CdtTrfTxInf>".length());
        byte[] batch=single.replace(transfer,transfer+transfer.replace("END-BATCH","END-BATCH-2")).replace("<NbOfTxs>1</NbOfTxs>","<NbOfTxs>2</NbOfTxs>").replace("<CtrlSum>4.00</CtrlSum>","<CtrlSum>8.00</CtrlSum>").getBytes(StandardCharsets.UTF_8);
        long count=orders.count();uploads.receive(request(batch,material.signature().getPrivate()));uploads.receive(request(batch,material.signature().getPrivate()));
        assertThat(orders.count()).isEqualTo(count+2);
        byte[] invalid=new String(batch,StandardCharsets.UTF_8).replace("BATCH","BAD-BATCH").replace("END-BAD-BATCH-2","END-BAD-BATCH-2").replace("<CtrlSum>8.00</CtrlSum>","<CtrlSum>9.00</CtrlSum>").getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(()->uploads.receive(request(invalid,material.signature().getPrivate()))).isInstanceOf(IllegalArgumentException.class);
        assertThat(orders.count()).isEqualTo(count+2);
    }
    @Test void scheduledPaymentBooksExactlyOnceWhenDue()throws Exception{
        uploads.receive(request(pain("DUE-ONCE","8.00",LocalDate.now().plusDays(1)),material.signature().getPrivate()));
        var order=orders.findAll().stream().filter(o->"DUE-ONCE".equals(o.getOriginalMessageId())).findFirst().orElseThrow();
        assertThat(order.getStatus()).isEqualTo(PaymentOrderStatus.SCHEDULED);
        String pending=new de.ostms.evils.ebics.camt.Pain002Generator().generate(List.of(order));statusValidator.validate(pending);
        assertThat(pending).contains("<TxSts>ACSP</TxSts>");
        long before=ledger.count();paymentService.bookDuePayments();assertThat(ledger.count()).isEqualTo(before);
        org.springframework.test.util.ReflectionTestUtils.setField(order,"executionDate",LocalDate.now());orders.save(order);
        paymentService.bookDuePayments();paymentService.bookDuePayments();
        assertThat(ledger.count()).isEqualTo(before+1);
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(PaymentOrderStatus.APPROVED);
        assertThat(paymentService.detail(order.getId()).events().stream().filter(e->e.getKind().equals("BOOKED")).count()).isEqualTo(1);
    }
    @Test void negativeReceiptDoesNotMarkStatusDelivered()throws Exception{
        uploads.receive(request(pain("NEG-RECEIPT","1.00",LocalDate.now()),material.signature().getPrivate()));
        var order=orders.findAll().stream().filter(o->"NEG-RECEIPT".equals(o.getOriginalMessageId())).findFirst().orElseThrow();
        String response=controller.receive(reportRequest("PSR","PAYPARTNER")).getBody();
        String tx=HpbCodec.text(HpbCodec.parse(response.getBytes(StandardCharsets.UTF_8)).getDocumentElement(),"TransactionID");
        assertThat(controller.receive(receipt(tx,1)).getBody()).contains("011000");
        assertThat(orders.findById(order.getId()).orElseThrow().getStatusReportRetrievedAt()).isNull();
        var parser=new de.ostms.evils.ebics.protocol.EbicsRequestParser();
        assertThatThrownBy(()->reports.receipt(parser.parse(receipt(tx,0)))).isInstanceOf(de.ostms.evils.ebics.protocol.EbicsProtocolException.class);
    }

    // ---- EBICS 2.5 (H004) classic order types ----
    String h004Download(String orderType)throws Exception{
        return EbicsAuthentication.sign("<ebicsRequest xmlns=\"urn:org:ebics:H004\" Version=\"H004\" Revision=\"1\"><header authenticate=\"true\"><static><HostID>EVILSBANK</HostID><PartnerID>PAYPARTNER</PartnerID><UserID>"+user+"</UserID><OrderDetails><OrderType>"+orderType+"</OrderType><OrderAttribute>DZHNN</OrderAttribute><StandardOrderParams><DateRange><Start>"+LocalDate.now().minusDays(1)+"</Start><End>"+LocalDate.now()+"</End></DateRange></StandardOrderParams></OrderDetails></static><mutable><TransactionPhase>Initialisation</TransactionPhase></mutable></header><AuthSignature/><body/></ebicsRequest>",material.authentication().getPrivate());
    }
    String h004CctInit()throws Exception{
        return EbicsAuthentication.sign("<ebicsRequest xmlns=\"urn:org:ebics:H004\" Version=\"H004\" Revision=\"1\"><header authenticate=\"true\"><static><HostID>EVILSBANK</HostID><PartnerID>PAYPARTNER</PartnerID><UserID>"+user+"</UserID><Timestamp>"+Instant.now()+"</Timestamp><OrderDetails><OrderType>CCT</OrderType><OrderAttribute>OZHNN</OrderAttribute><StandardOrderParams/></OrderDetails></static><mutable><TransactionPhase>Initialisation</TransactionPhase></mutable></header><AuthSignature/><body/></ebicsRequest>",material.authentication().getPrivate());
    }
    String h004Transfer(String transactionId,byte[] pain)throws Exception {
        String transfer=HpbCodec.encrypt(pain,bank.encryption());
        return EbicsAuthentication.sign("<ebicsRequest xmlns=\"urn:org:ebics:H004\" Version=\"H004\" Revision=\"1\"><header authenticate=\"true\"><static><TransactionID>"+transactionId+"</TransactionID></static><mutable><TransactionPhase>Transfer</TransactionPhase><SegmentNumber lastSegment=\"true\">1</SegmentNumber></mutable></header><AuthSignature/><body>"+transfer+"</body></ebicsRequest>",material.authentication().getPrivate());
    }
    String decryptedDownload(String response)throws Exception {
        var document=HpbCodec.parse(response.getBytes(StandardCharsets.UTF_8));
        EbicsAuthentication.verify(document,bank.authentication());
        return new String(PaymentWire.decrypt(document.getDocumentElement(),material.encryption().getPrivate()),StandardCharsets.UTF_8);
    }
    @Test void h004CctUploadIsAcceptedBookedAndAnsweredInH004()throws Exception {
        long before=ledger.count(), orderCount=orders.count();
        byte[] payload=pain("H004-CCT","7.77",LocalDate.now());
        String initial=controller.receive(h004CctInit()).getBody();
        de.ostms.evils.ebics.protocol.SchemaAssertions.responseH004(initial);
        assertThat(initial).contains("urn:org:ebics:H004","Version=\"H004\"");
        String transactionId=HpbCodec.text(HpbCodec.parse(initial.getBytes(StandardCharsets.UTF_8)).getDocumentElement(),"TransactionID");
        assertThat(transactionId).isNotBlank();
        String response=controller.receive(h004Transfer(transactionId,payload)).getBody();
        de.ostms.evils.ebics.protocol.SchemaAssertions.responseH004(response);
        assertThat(response).contains("urn:org:ebics:H004","000000","gebucht");
        assertThat(ledger.count()).isEqualTo(before+1);assertThat(orders.count()).isEqualTo(orderCount+1);
    }
    @Test void h004StaDownloadDeliversMt940InTheFormatOsTmsParses()throws Exception {
        String response=controller.receive(h004Download("STA")).getBody();
        de.ostms.evils.ebics.protocol.SchemaAssertions.responseH004(response);
        assertThat(response).contains("urn:org:ebics:H004","000000");
        String mt940=decryptedDownload(response);
        assertThat(mt940).contains(":20:EVILS",":25:"+IBAN,":28C:",":60F:",":62F:").endsWith("-\r\n");
        for(String line:mt940.split("\r\n"))assertThat(line.length()).isLessThanOrEqualTo(65);
    }
    @Test void h004C53DownloadDeliversValidCamt053()throws Exception {
        String response=controller.receive(h004Download("C53")).getBody();
        de.ostms.evils.ebics.protocol.SchemaAssertions.responseH004(response);
        String camt=decryptedDownload(response);
        de.ostms.evils.ebics.protocol.SchemaAssertions.camt(camt);
        assertThat(camt).contains("<IBAN>"+IBAN+"</IBAN>");
    }
    byte[] decryptedDownloadBytes(String response)throws Exception {
        var document=HpbCodec.parse(response.getBytes(StandardCharsets.UTF_8));
        EbicsAuthentication.verify(document,bank.authentication());
        return PaymentWire.decrypt(document.getDocumentElement(),material.encryption().getPrivate());
    }
    @Test void h004ZipCamtDownloadsDeliverOneValidCamtFilePerAccount()throws Exception {
        for(String[] type:new String[][]{{"Z52","camt052"},{"Z53","camt053"},{"Z54","camt054"}}){
            String response=controller.receive(h004Download(type[0])).getBody();
            de.ostms.evils.ebics.protocol.SchemaAssertions.responseH004(response);
            assertThat(response).contains("urn:org:ebics:H004","000000");
            byte[] clear=decryptedDownloadBytes(response);
            assertThat(clear[0]).as(type[0]+" is a ZIP container").isEqualTo((byte)'P');
            assertThat(clear[1]).isEqualTo((byte)'K');
            List<String> names=new ArrayList<>(),files=new ArrayList<>();
            try(var zip=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(clear))){
                for(var entry=zip.getNextEntry();entry!=null;entry=zip.getNextEntry()){names.add(entry.getName());files.add(new String(zip.readAllBytes(),StandardCharsets.UTF_8));}
            }
            assertThat(names).isNotEmpty().allSatisfy(n->assertThat(n).startsWith(type[1]+"_").endsWith(".xml"));
            assertThat(names).anySatisfy(n->assertThat(n).contains(IBAN));
            // Only a camt.053 XSD is bundled: validate Z53 against it, check namespace and well-formedness for the others
            for(String file:files){
                if("Z53".equals(type[0]))de.ostms.evils.ebics.protocol.SchemaAssertions.camt(file);
                else{HpbCodec.parse(file.getBytes(StandardCharsets.UTF_8));assertThat(file).contains("urn:iso:std:iso:20022:tech:xsd:"+type[1].replace("camt","camt."));}
            }
            assertThat(files).anySatisfy(f->assertThat(f).contains("<IBAN>"+IBAN+"</IBAN>"));
        }
    }
    @Test void h004VmkDownloadDeliversTheIntradayMt942Report()throws Exception {
        uploads.receive(request(pain("VMK-INTRADAY","2.34",LocalDate.now()),material.signature().getPrivate()));
        String response=controller.receive(h004Download("VMK")).getBody();
        de.ostms.evils.ebics.protocol.SchemaAssertions.responseH004(response);
        assertThat(response).contains("urn:org:ebics:H004","000000");
        String mt942=decryptedDownload(response);
        assertThat(mt942).contains(":20:EVILS",":25:"+IBAN,":28C:",":34F:EUR0,",":13D:",":61:",":90D:",":90C:").endsWith("-\r\n").doesNotContain(":60F:",":62F:");
        for(String line:mt942.split("\r\n"))assertThat(line.length()).isLessThanOrEqualTo(65);
    }
    @Test void h004ZipAndVmkDownloadsHonourTheDownloadPermission()throws Exception {
        var participant=participants.findById(user).orElseThrow();
        try{
            participant.setDownloadAllowed(false);participants.save(participant);
            for(String type:new String[]{"Z53","VMK"})assertThat(controller.receive(h004Download(type)).getBody()).contains("090003");
        }finally{participant.setDownloadAllowed(true);participants.save(participant);}
    }
    @Test void h004ClassicOrderTypeOfTheGermanProfileThatIsNotImplementedIsAnsweredExplicitly()throws Exception {
        String response=controller.receive(h004Download("CDD")).getBody();
        assertThat(response).contains("urn:org:ebics:H004","091005").doesNotContain("<TransactionID>");
    }
    @Test void h004DownloadAndUploadHonourThePerParticipantPermissions()throws Exception {
        var participant=participants.findById(user).orElseThrow();
        try{
            participant.setDownloadAllowed(false);participants.save(participant);
            assertThat(controller.receive(h004Download("STA")).getBody()).contains("090003");
            participant.setDownloadAllowed(true);participant.setUploadAllowed(false);participants.save(participant);
            String denied=controller.receive(h004CctInit()).getBody();
            assertThat(denied).contains("urn:org:ebics:H004","090003");
            assertThat(controller.receive(h004Download("STA")).getBody()).doesNotContain("090003");
        }finally{
            participant.setDownloadAllowed(true);participant.setUploadAllowed(true);participants.save(participant);
        }
    }
    String h004Receipt(String tx)throws Exception{
        return EbicsAuthentication.sign("<ebicsRequest xmlns=\"urn:org:ebics:H004\" Version=\"H004\" Revision=\"1\"><header authenticate=\"true\"><static><TransactionID>"+tx+"</TransactionID></static><mutable><TransactionPhase>Receipt</TransactionPhase></mutable></header><AuthSignature/><body><TransferReceipt authenticate=\"true\"><ReceiptCode>0</ReceiptCode></TransferReceipt></body></ebicsRequest>",material.authentication().getPrivate());
    }
    @Test void h004Z01StatusReportDownloadAndReceiptRoundTrip()throws Exception {
        accounts.save(new Account("EVILSBANK",INTERNAL_IBAN,"INGDDEFFXXX","PAYPARTNER","Recipient",BigDecimal.ZERO,LocalDate.now(),"EUR",true));
        byte[] payload=painTo("H004-Z01","3.21",LocalDate.now(),INTERNAL_IBAN);
        String initial=controller.receive(h004CctInit()).getBody();
        String tx=HpbCodec.text(HpbCodec.parse(initial.getBytes(StandardCharsets.UTF_8)).getDocumentElement(),"TransactionID");
        assertThat(controller.receive(h004Transfer(tx,payload)).getBody()).contains("000000");
        var order=orders.findAll().stream().filter(o->"H004-Z01".equals(o.getOriginalMessageId())).findFirst().orElseThrow();

        String report=controller.receive(h004Download("Z01")).getBody();
        assertThat(report).contains("urn:org:ebics:H004");
        assertThat(decryptReports(report)).anySatisfy(file->assertThat(file).contains("<OrgnlMsgId>H004-Z01</OrgnlMsgId>","<TxSts>ACSC</TxSts>"));
        assertThat(orders.findById(order.getId()).orElseThrow().getStatusReportRetrievedAt()).isNull();

        String downloadId=HpbCodec.text(HpbCodec.parse(report.getBytes(StandardCharsets.UTF_8)).getDocumentElement(),"TransactionID");
        String ack=controller.receive(h004Receipt(downloadId)).getBody();
        de.ostms.evils.ebics.protocol.SchemaAssertions.responseH004(ack);
        assertThat(ack).contains("urn:org:ebics:H004","011000");
        assertThat(orders.findById(order.getId()).orElseThrow().getStatusReportRetrievedAt()).isNotNull();
    }
}
