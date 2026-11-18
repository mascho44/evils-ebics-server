package de.ostms.evils.ebics.config;

import de.ostms.evils.ebics.security.TotpService;
import de.ostms.evils.ebics.service.AdminUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.net.URI;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end login regression for optional SUPERADMIN 2FA (see TotpAuthenticationProvider/
 * TotpLoginFilter) - real HTTP against the actual SecurityFilterChain, not a mock, since a wiring
 * mistake here (e.g. the default DaoAuthenticationProvider sneaking back in alongside
 * TotpAuthenticationProvider) would silently let a 2FA-enabled account log in with just its
 * password, which no unit-level test of TotpService alone could catch.
 */
@org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class TotpLoginTest {

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private AdminUserService adminUserService;
    @Autowired
    private TotpService totpService;

    private String username;
    private String password;

    @BeforeEach
    void setUp() {
        username = "totptest-" + UUID.randomUUID().toString().substring(0, 8);
        password = "supersecret123";
        adminUserService.create(null, username, "TOTP Test", password, "ADMIN");
        rest.getRestTemplate().setRequestFactory(new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(java.net.HttpURLConnection connection, String httpMethod) throws java.io.IOException {
                super.prepareConnection(connection, httpMethod);
                connection.setInstanceFollowRedirects(false);
            }
        });
    }

    private ResponseEntity<String> login(String user, String pass, String totp) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("username", user);
        form.add("password", pass);
        if (totp != null) form.add("totp", totp);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        RequestEntity<MultiValueMap<String, String>> request = RequestEntity.post(URI.create("/login"))
                .headers(headers).body(form);
        return rest.exchange(request, String.class);
    }

    private static boolean isSuccessfulLogin(ResponseEntity<String> response) {
        return response.getStatusCode() == HttpStatus.FOUND
                && "/admin.html".equals(response.getHeaders().getLocation().getPath());
    }

    private static boolean isFailedLogin(ResponseEntity<String> response) {
        return response.getStatusCode() == HttpStatus.FOUND
                && response.getHeaders().getLocation().getPath().equals("/login-error.html");
    }

    @Test
    void accountWithout2faLogsInWithJustPassword() {
        ResponseEntity<String> response = login(username, password, null);

        assertThat(isSuccessfulLogin(response)).isTrue();
    }

    /** Regression for the actual root cause of a real production incident (2026-09-26):
     * TotpLoginFilter, built manually instead of via HttpSecurity's formLogin() DSL, never
     * received the session-backed SecurityContextRepository the DSL wires in automatically - it
     * fell back to AbstractAuthenticationProcessingFilter's own default
     * (RequestAttributeSecurityContextRepository), which only keeps the authenticated context
     * alive for the single request/response handling the redirect, then discards it. Every login
     * therefore "succeeded" (the 302 to /admin.html happened, and TotpAuthenticationProvider's own
     * log confirmed success) but the very NEXT request was already unauthenticated again, bouncing
     * back to the login page forever - indistinguishable from a login that silently never worked,
     * no matter how many times the correct password was entered. This is exactly the gap the other
     * tests in this class missed: they only ever check the FIRST response to the login POST, never
     * a follow-up request using the session it (claims to have) established. */
    @Test
    void sessionFromASuccessfulLoginActuallyPersistsToTheNextRequest() {
        ResponseEntity<String> loginResponse = login(username, password, null);
        assertThat(isSuccessfulLogin(loginResponse)).isTrue();
        String sessionCookie = loginResponse.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(sessionCookie).as("login response must set a session cookie").isNotNull();

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, sessionCookie);
        RequestEntity<Void> adminPageRequest = RequestEntity.get(URI.create("/admin.html")).headers(headers).build();
        ResponseEntity<String> adminPageResponse = rest.exchange(adminPageRequest, String.class);

        assertThat(adminPageResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void wrongPasswordIsRejectedRegardlessOf2faState() {
        ResponseEntity<String> response = login(username, "wrong-password", null);

        assertThat(isFailedLogin(response)).isTrue();
    }

    @Test
    void accountWith2faEnabledCannotLogInWithPasswordAlone() {
        AdminUserService.TotpSetup setup = adminUserService.beginTotpSetup(username);
        adminUserService.confirmTotpSetup(username, currentCode(setup.secret()));

        ResponseEntity<String> response = login(username, password, null);

        assertThat(isFailedLogin(response)).isTrue();
    }

    @Test
    void accountWith2faEnabledCannotLogInWithWrongCode() {
        AdminUserService.TotpSetup setup = adminUserService.beginTotpSetup(username);
        adminUserService.confirmTotpSetup(username, currentCode(setup.secret()));

        ResponseEntity<String> response = login(username, password, "000000".equals(currentCode(setup.secret())) ? "111111" : "000000");

        assertThat(isFailedLogin(response)).isTrue();
    }

    @Test
    void accountWith2faEnabledLogsInWithCorrectCode() {
        AdminUserService.TotpSetup setup = adminUserService.beginTotpSetup(username);
        adminUserService.confirmTotpSetup(username, currentCode(setup.secret()));

        ResponseEntity<String> response = login(username, password, currentCode(setup.secret()));

        assertThat(isSuccessfulLogin(response)).isTrue();
    }

    /** Regression for a real production incident (2026-09-26): TotpLoginFilter's request matcher
     * originally matched ANY HTTP method on /login, so a plain GET (e.g. admin.html's client-side
     * code navigating there after a stale/expired session) was treated as a login ATTEMPT with
     * empty username/password - which of course failed, redirecting to the error page and making
     * it look like login was broken even right after a genuinely successful one elsewhere. */
    @Test
    void plainGetToLoginIsNotTreatedAsAFailedLoginAttempt() {
        ResponseEntity<String> response = rest.getForEntity("/login", String.class);

        assertThat(isFailedLogin(response)).isFalse();
    }

    private String currentCode(String secret) {
        long step = java.time.Instant.now().getEpochSecond() / 30;
        return TotpService.codeAt(secret, step);
    }
}
