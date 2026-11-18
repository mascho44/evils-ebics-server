package de.ostms.evils.ebics.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;

/** Separate Spring context (own @TestPropertySource) from TenantRequestServiceTest, damit
 * evils.mail.admin-benachrichtigung dort testweise gesetzt werden kann, ohne die anderen Tests zu
 * beeinflussen, die bewusst pruefen, dass OHNE diese Property keine Admin-Mail verschickt wird. */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "evils.mail.admin-benachrichtigung=admin@example.com")
class TenantRequestServiceAdminNotificationTest {

    @Autowired
    private TenantRequestService service;
    @MockitoBean
    private JavaMailSender javaMailSender;

    @Test
    void submitNotifiesTheConfiguredAdminAddress() {
        String hostId = "REQBANK-" + UUID.randomUUID().toString().substring(0, 8);

        service.submit(hostId, "Requested Bank", null, null, "user-" + hostId, "Requester",
                "supersecret123", "requester@example.com");

        verify(javaMailSender).send(argThat((SimpleMailMessage message) ->
                message.getTo() != null
                        && message.getTo()[0].equals("admin@example.com")
                        && message.getSubject() != null
                        && message.getSubject().contains(hostId)));
    }
}
