package de.ostms.evils.ebics.service;

import de.ostms.evils.ebics.domain.TenantRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@SpringBootTest
@ActiveProfiles("test")
class TenantRequestServiceTest {

    @Autowired
    private TenantRequestService service;
    @Autowired
    private BankService bankService;
    @Autowired
    private AdminUserService adminUserService;
    @Autowired
    private PasswordEncoder encoder;
    @MockitoBean
    private JavaMailSender javaMailSender;

    private String uniqueHostId() {
        return "REQBANK-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private TenantRequest submit(String hostId, String username, String password, String email) {
        return service.submit(hostId, "Requested Bank", null, "REQTDEFFXXX", username, "Requester", password, email);
    }

    @Test
    void submitCreatesAPendingRequestWithHashedPasswordAndStoredEmail() {
        String hostId = uniqueHostId();
        TenantRequest request = submit(hostId, "requser-" + hostId, "supersecret123", "requester@example.com");

        assertThat(request.getId()).isNotNull();
        assertThat(request.getStatus()).isEqualTo("PENDING");
        assertThat(request.getRequesterEmail()).isEqualTo("requester@example.com");
        assertThat(request.getAdminPasswordHash()).isNotEqualTo("supersecret123");
        assertThat(encoder.matches("supersecret123", request.getAdminPasswordHash())).isTrue();
    }

    @Test
    void submitRejectsMissingOrMalformedEmail() {
        String hostId = uniqueHostId();
        assertThatThrownBy(() -> submit(hostId, "user-" + hostId, "supersecret123", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> submit(hostId, "user-" + hostId, "supersecret123", "keine-email"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void submitRejectsShortPassword() {
        assertThatThrownBy(() -> submit(uniqueHostId(), "u", "short", "a@b.de"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void submitRejectsDuplicatePendingHostId() {
        String hostId = uniqueHostId();
        submit(hostId, "user1-" + hostId, "supersecret123", "a@b.de");

        assertThatThrownBy(() -> submit(hostId, "user2-" + hostId, "supersecret123", "c@d.de"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void submitRejectsHostIdOfAnAlreadyExistingBank() {
        assertThatThrownBy(() -> submit("EVILSBANK", "someuser", "supersecret123", "a@b.de"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void approveCreatesLoginTenantAndAdminUserAndNotifiesRequesterByMail() {
        String hostId = uniqueHostId();
        String username = "admin-" + hostId;
        TenantRequest request = submit(hostId, username, "myrequestpassword", "requester@example.com");

        TenantRequest approved = service.approve(request.getId(), "superadmin1");

        assertThat(approved.getStatus()).isEqualTo("APPROVED");
        assertThat(approved.getDecidedBy()).isEqualTo("superadmin1");
        assertThat(bankService.findBank(hostId)).isPresent();
        var login = adminUserService.loadUserByUsername(username);
        assertThat(encoder.matches("myrequestpassword", login.getPassword())).isTrue();
        verify(javaMailSender).send(any(SimpleMailMessage.class));
    }

    @Test
    void approvingTwiceFails() {
        String hostId = uniqueHostId();
        TenantRequest request = submit(hostId, "user-" + hostId, "supersecret123", "a@b.de");
        service.approve(request.getId(), "superadmin1");

        assertThatThrownBy(() -> service.approve(request.getId(), "superadmin1"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectMarksStatusAndReasonAndNotifiesRequesterByMail() {
        String hostId = uniqueHostId();
        TenantRequest request = submit(hostId, "user-" + hostId, "supersecret123", "requester@example.com");

        TenantRequest rejected = service.reject(request.getId(), "superadmin1", "Kein Bedarf erkennbar");

        assertThat(rejected.getStatus()).isEqualTo("REJECTED");
        assertThat(rejected.getRejectionReason()).isEqualTo("Kein Bedarf erkennbar");
        assertThat(bankService.findBank(hostId)).isEmpty();
        verify(javaMailSender).send(any(SimpleMailMessage.class));
    }

    @Test
    void pendingListDoesNotIncludeDecidedRequests() {
        String hostId = uniqueHostId();
        TenantRequest request = submit(hostId, "user-" + hostId, "supersecret123", "a@b.de");
        service.reject(request.getId(), "superadmin1", null);

        assertThat(service.pending()).noneMatch(r -> r.getId().equals(request.getId()));
    }

    @Test
    void submitDoesNotSendMailUntilDecided() {
        submit(uniqueHostId(), "user-" + UUID.randomUUID(), "supersecret123", "a@b.de");

        verify(javaMailSender, never()).send(any(SimpleMailMessage.class));
    }
}
