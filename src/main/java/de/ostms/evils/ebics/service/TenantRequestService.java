package de.ostms.evils.ebics.service;

import de.ostms.evils.ebics.domain.AdminUser;
import de.ostms.evils.ebics.domain.Bank;
import de.ostms.evils.ebics.domain.TenantRequest;
import de.ostms.evils.ebics.persistence.AdminUserRepository;
import de.ostms.evils.ebics.persistence.TenantRequestRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Public self-service tenant signup: anyone can submit a request (see
 * de.ostms.evils.ebics.admin.TenantSignupController), but nothing (Bank, AdminUser) actually exists
 * until a SUPERADMIN approves it (de.ostms.evils.ebics.admin.TenantRequestController). No rate-limiting
 * against spam requests by design for this round - manual approval is the abuse guard.
 * requesterEmail is used purely to notify the requester of the decision (see approve/reject) -
 * a MailService failure there never affects the approval/rejection outcome itself.
 */
@Service
public class TenantRequestService {

    /** Deliberately permissive (no full RFC 5322 validation) - just enough to catch an obviously
     * malformed value before it's used as a mail recipient. */
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final TenantRequestRepository requests;
    private final AdminUserRepository adminUsers;
    private final BankService bankService;
    private final AdminUserService adminUserService;
    private final PasswordEncoder encoder;
    private final MailService mailService;
    private final String adminNotificationEmail;

    public TenantRequestService(TenantRequestRepository requests, AdminUserRepository adminUsers,
                                 BankService bankService, AdminUserService adminUserService,
                                 PasswordEncoder encoder, MailService mailService,
                                 @Value("${evils.mail.admin-benachrichtigung:}") String adminNotificationEmail) {
        this.requests = requests;
        this.adminUsers = adminUsers;
        this.bankService = bankService;
        this.adminUserService = adminUserService;
        this.encoder = encoder;
        this.mailService = mailService;
        this.adminNotificationEmail = adminNotificationEmail;
    }

    @Transactional
    public TenantRequest submit(String hostId, String hostName, String protocolVersion, String bic,
                                 String adminUsername, String adminDisplayName, String adminPassword,
                                 String requesterEmail) {
        if (hostId == null || hostId.isBlank() || hostName == null || hostName.isBlank()) {
            throw new IllegalArgumentException("hostId and hostName are required");
        }
        if (adminUsername == null || adminUsername.isBlank() || adminPassword == null || adminPassword.length() < 8) {
            throw new IllegalArgumentException("adminUsername and a password with at least 8 characters are required");
        }
        if (requesterEmail == null || !EMAIL_PATTERN.matcher(requesterEmail).matches()) {
            throw new IllegalArgumentException("Eine gueltige E-Mail-Adresse ist erforderlich.");
        }
        if (bankService.findBank(hostId).isPresent() || requests.existsByHostIdAndStatus(hostId, "PENDING")) {
            throw new IllegalArgumentException("Diese Host-ID ist bereits vergeben oder angefragt.");
        }
        if (adminUsers.existsById(adminUsername) || requests.existsByAdminUsernameAndStatus(adminUsername, "PENDING")) {
            throw new IllegalArgumentException("Dieser Admin-Benutzername ist bereits vergeben oder angefragt.");
        }
        TenantRequest request = new TenantRequest(hostId, hostName,
                protocolVersion == null || protocolVersion.isBlank() ? "H005" : protocolVersion, bic,
                adminUsername, adminDisplayName, encoder.encode(adminPassword), requesterEmail);
        request = requests.save(request);

        if (adminNotificationEmail != null && !adminNotificationEmail.isBlank()) {
            mailService.senden(adminNotificationEmail, "Neuer Sandbox-Antrag: " + hostId,
                    "Ein neuer Mandanten-Antrag wartet auf Pruefung.\n\nHost-ID: " + hostId
                            + "\nBankname: " + hostName + "\nAdmin-Benutzername: " + adminUsername
                            + "\nAnfragende E-Mail: " + requesterEmail
                            + "\n\nBitte in der Admin-Oberflaeche unter \"Mandanten\" -> \"Offene Anfragen\" pruefen.");
        }
        return request;
    }

    public List<TenantRequest> pending() {
        return requests.findAllByStatusOrderByRequestedAtDesc("PENDING");
    }

    public List<TenantRequest> all() {
        return requests.findAllByOrderByRequestedAtDesc();
    }

    @Transactional
    public TenantRequest approve(Long id, String actingUsername) {
        TenantRequest request = requests.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown tenant request: " + id));
        if (!"PENDING".equals(request.getStatus())) {
            throw new IllegalStateException("Diese Anfrage wurde bereits entschieden.");
        }
        // Re-check availability: time has passed since submission, and a SUPERADMIN could have
        // meanwhile created a tenant/admin with the same hostId/username manually (see
        // TenantController), which createBank/createWithHash below would otherwise only catch
        // with a less specific "Duplicate HostID"/"Username already exists" error.
        if (bankService.findBank(request.getHostId()).isPresent()) {
            throw new IllegalStateException("Die Host-ID wurde inzwischen anderweitig vergeben.");
        }
        if (adminUsers.existsById(request.getAdminUsername())) {
            throw new IllegalStateException("Der Admin-Benutzername wurde inzwischen anderweitig vergeben.");
        }
        Bank bank = bankService.createBank(request.getHostId(), request.getHostName(),
                request.getProtocolVersion(), request.getBic());
        AdminUser admin = adminUserService.createWithHash(bank.hostId(), request.getAdminUsername(),
                request.getAdminDisplayName(), request.getAdminPasswordHash(), "ADMIN");
        request.approve(actingUsername);

        benachrichtigen(request, "Ihr Mandant wurde freigeschaltet",
                "Ihre Anfrage fuer den Mandanten \"" + request.getHostName() + "\" (Host-ID " + request.getHostId()
                        + ") wurde freigeschaltet.\n\nSie koennen sich jetzt mit dem Benutzernamen \""
                        + admin.getUsername() + "\" und dem von Ihnen gewaehlten Passwort in der Admin-Oberflaeche anmelden.");
        return request;
    }

    @Transactional
    public TenantRequest reject(Long id, String actingUsername, String reason) {
        TenantRequest request = requests.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown tenant request: " + id));
        if (!"PENDING".equals(request.getStatus())) {
            throw new IllegalStateException("Diese Anfrage wurde bereits entschieden.");
        }
        request.reject(actingUsername, reason);

        String text = "Ihre Anfrage fuer den Mandanten \"" + request.getHostName() + "\" (Host-ID "
                + request.getHostId() + ") wurde leider abgelehnt."
                + (reason == null || reason.isBlank() ? "" : "\n\nBegruendung: " + reason);
        benachrichtigen(request, "Ihre Mandanten-Anfrage wurde abgelehnt", text);
        return request;
    }

    private void benachrichtigen(TenantRequest request, String betreff, String text) {
        if (request.getRequesterEmail() != null && !request.getRequesterEmail().isBlank()) {
            mailService.senden(request.getRequesterEmail(), betreff, text);
        }
    }
}
