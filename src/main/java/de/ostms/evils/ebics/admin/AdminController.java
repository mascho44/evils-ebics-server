package de.ostms.evils.ebics.admin;

import de.ostms.evils.ebics.domain.*;
import de.ostms.evils.ebics.service.BankService;
import de.ostms.evils.ebics.service.KeyManagementService;
import de.ostms.evils.ebics.service.PaymentAuthorizationService;
import org.springframework.web.bind.annotation.*;

import java.util.Collection;
import org.springframework.http.HttpStatus;

/** Every endpoint here is tenant-scoped: hostId comes from TenantResolver.effectiveHostId (the
 * logged-in AdminUser's own tenant, or an explicit X-Tenant-Host-Id header for a SUPERADMIN) -
 * see TenantResolver and de.ostms.evils.ebics.security.TenantContext for why. */
@RestController
@RequestMapping("/admin/api")
public class AdminController {

    private final BankService bankService;
    private final PaymentAuthorizationService paymentAuthorizationService;
    private final KeyManagementService keyManagementService;

    public AdminController(BankService bankService, PaymentAuthorizationService paymentAuthorizationService,
                            KeyManagementService keyManagementService) {
        this.bankService = bankService;
        this.paymentAuthorizationService = paymentAuthorizationService;
        this.keyManagementService = keyManagementService;
    }

    @GetMapping("/bank")
    public Bank bank(@RequestHeader(value = "X-Tenant-Host-Id", required = false) String tenantHeader) {
        return bankService.bank(TenantResolver.effectiveHostId(tenantHeader, bankService));
    }

    @GetMapping("/customers")
    public Collection<Customer> customers(@RequestHeader(value = "X-Tenant-Host-Id", required = false) String tenantHeader) {
        return bankService.customersForTenant(TenantResolver.effectiveHostId(tenantHeader, bankService));
    }

    @PostMapping("/customers")
    @ResponseStatus(HttpStatus.CREATED)
    public Customer createCustomer(@RequestBody CreateCustomerRequest request,
                                    @RequestHeader(value = "X-Tenant-Host-Id", required = false) String tenantHeader) {
        return bankService.createCustomer(TenantResolver.effectiveHostId(tenantHeader, bankService), request.partnerId(), request.name());
    }

    @GetMapping("/participants")
    public Collection<Participant> participants(@RequestHeader(value = "X-Tenant-Host-Id", required = false) String tenantHeader) {
        return bankService.participantsForTenant(TenantResolver.effectiveHostId(tenantHeader, bankService));
    }

    @PostMapping("/participants")
    @ResponseStatus(HttpStatus.CREATED)
    public Participant createParticipant(@RequestBody CreateParticipantRequest request,
                                          @RequestHeader(value = "X-Tenant-Host-Id", required = false) String tenantHeader) {
        return bankService.createParticipant(TenantResolver.effectiveHostId(tenantHeader, bankService), request.userId(), request.partnerId(), request.name());
    }

    @PatchMapping("/participants/{userId}/active")
    public Participant setParticipantActive(@PathVariable String userId, @RequestParam boolean value,
                                             @RequestHeader(value = "X-Tenant-Host-Id", required = false) String tenantHeader) {
        return bankService.setParticipantActive(TenantResolver.effectiveHostId(tenantHeader, bankService), userId, value);
    }

    /** Upload-/Download-/MT7xx-Berechtigung eines Teilnehmers - jeder Parameter
     * optional, nur die angegebenen werden geaendert. */
    @PatchMapping("/participants/{userId}/permissions")
    public Participant setParticipantPermissions(@PathVariable String userId,
                                                  @RequestParam(required = false) Boolean upload,
                                                  @RequestParam(required = false) Boolean download,
                                                  @RequestParam(required = false) Boolean lcDownload,
                                                  @RequestHeader(value = "X-Tenant-Host-Id", required = false) String tenantHeader) {
        return bankService.setParticipantPermissions(TenantResolver.effectiveHostId(tenantHeader, bankService),
                userId, upload, download, lcDownload);
    }

    @DeleteMapping("/participants/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteParticipant(@PathVariable String userId,
                                   @RequestHeader(value = "X-Tenant-Host-Id", required = false) String tenantHeader) {
        bankService.deleteParticipant(TenantResolver.effectiveHostId(tenantHeader, bankService), userId);
    }

    /** Deletes the participant's stored INI/HIA public keys and reverts their key status to
     * NOT_INITIALIZED, so a real EBICS client can re-run INI/HIA/HPB from scratch (e.g. after the
     * customer lost their key material) - see KeyManagementService.reset. */
    @PostMapping("/participants/{userId}/reset")
    public Participant resetParticipant(@PathVariable String userId,
                                         @RequestHeader(value = "X-Tenant-Host-Id", required = false) String tenantHeader) {
        requireOwnedParticipant(TenantResolver.effectiveHostId(tenantHeader, bankService), userId);
        return keyManagementService.reset(userId);
    }

    @GetMapping("/participants/{userId}/payment-authorization")
    public PaymentAuthorization paymentAuthorization(@PathVariable String userId, @RequestParam(required = false) String iban,
                                                       @RequestHeader(value = "X-Tenant-Host-Id", required = false) String tenantHeader) {
        requireOwnedParticipant(TenantResolver.effectiveHostId(tenantHeader, bankService), userId);
        return iban == null || iban.isBlank() ? paymentAuthorizationService.forUser(userId) : paymentAuthorizationService.forAccount(userId, iban);
    }

    /** All authorizations held by a user: the account-wide default plus any per-account
     * overrides, default first. */
    @GetMapping("/participants/{userId}/payment-authorizations")
    public Collection<PaymentAuthorization> paymentAuthorizations(@PathVariable String userId,
                                                                     @RequestHeader(value = "X-Tenant-Host-Id", required = false) String tenantHeader) {
        requireOwnedParticipant(TenantResolver.effectiveHostId(tenantHeader, bankService), userId);
        return paymentAuthorizationService.listForUser(userId);
    }

    @DeleteMapping("/participants/{userId}/payment-authorization")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePaymentAuthorizationOverride(@PathVariable String userId, @RequestParam String iban,
                                                     @RequestHeader(value = "X-Tenant-Host-Id", required = false) String tenantHeader) {
        requireOwnedParticipant(TenantResolver.effectiveHostId(tenantHeader, bankService), userId);
        paymentAuthorizationService.removeOverride(userId, iban);
    }

    @PostMapping("/participants/{userId}/signature-release")
    public Participant releaseSignatureKey(@PathVariable String userId,
                                            @RequestHeader(value = "X-Tenant-Host-Id", required = false) String tenantHeader) {
        requireOwnedParticipant(TenantResolver.effectiveHostId(tenantHeader, bankService), userId);
        return keyManagementService.releaseSignatureKey(userId);
    }

    @GetMapping("/participants/{userId}/keys")
    public Collection<ParticipantKey> participantKeys(@PathVariable String userId,
                                                        @RequestHeader(value = "X-Tenant-Host-Id", required = false) String tenantHeader) {
        requireOwnedParticipant(TenantResolver.effectiveHostId(tenantHeader, bankService), userId);
        return keyManagementService.keysOf(userId);
    }

    @PutMapping("/participants/{userId}/payment-authorization")
    public PaymentAuthorization setPaymentAuthorization(@PathVariable String userId, @RequestBody PaymentAuthorizationRequest request,
                                                          @RequestHeader(value = "X-Tenant-Host-Id", required = false) String tenantHeader) {
        requireOwnedParticipant(TenantResolver.effectiveHostId(tenantHeader, bankService), userId);
        return paymentAuthorizationService.set(userId, request.iban(), request.signatureClass());
    }

    @GetMapping("/accounts")
    public Collection<Account> accounts(@RequestHeader(value = "X-Tenant-Host-Id", required = false) String tenantHeader) {
        return bankService.accountsForTenant(TenantResolver.effectiveHostId(tenantHeader, bankService));
    }

    /** Fuer die "IBAN generieren"-Schaltflaeche im Testkonto-Anlegen-Dialog - liefert eine frische,
     * Mod-97-gueltige, noch unbenutzte deutsche Test-IBAN statt den Nutzer eine frei zu tippen zu
     * lassen (Quelle wiederkehrender "IBAN-Pruefsumme ist ungueltig"-Fehler bei spaeteren
     * Zahlungen). Braucht keinen Tenant-Header - die IBAN selbst ist noch keinem Konto zugeordnet. */
    public record GeneratedIban(String iban) {
    }

    @GetMapping("/accounts/generate-iban")
    public GeneratedIban generateIban() {
        return new GeneratedIban(bankService.generiereUnbenutzteTestIban());
    }

    @PostMapping("/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    public Account createAccount(@RequestBody CreateAccountRequest request,
                                  @RequestHeader(value = "X-Tenant-Host-Id", required = false) String tenantHeader) {
        String hostId = TenantResolver.effectiveHostId(tenantHeader, bankService);
        return bankService.createAccount(hostId, request.iban(), request.bic(), request.partnerId(),
                request.accountHolder(), request.openingBalance(), request.openingBalanceDate(), request.currency());
    }

    @DeleteMapping("/accounts/{iban}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAccount(@PathVariable String iban,
                               @RequestHeader(value = "X-Tenant-Host-Id", required = false) String tenantHeader) {
        bankService.deleteAccount(TenantResolver.effectiveHostId(tenantHeader, bankService), iban);
    }

    /** Participant-scoped sub-resources (keys, payment authorization, reset) go straight to
     * KeyManagementService/PaymentAuthorizationService by bare userId (see BankService's class
     * comment for why that's safe once ownership is confirmed) - this is the ownership check that
     * makes it safe: a tenant can't reach another tenant's participant just by guessing/knowing
     * its userId. */
    private void requireOwnedParticipant(String hostId, String userId) {
        bankService.findParticipant(hostId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown UserID: " + userId));
    }
}
