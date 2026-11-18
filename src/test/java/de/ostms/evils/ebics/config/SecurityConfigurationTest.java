package de.ostms.evils.ebics.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.MultiValueMap;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end regression test against the real security filter chain, not just unit-level
 * assertions - this feature strand has already had two production incidents caused purely by a
 * URL matcher being wrong (an endpoint wrongly locked or wrongly open), which no unit test could
 * have caught. Uses real HTTP over a random port (TestRestTemplate) specifically so the actual
 * SecurityFilterChain/matcher order from SecurityConfiguration is exercised, not a mock of it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SecurityConfigurationTest {

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private de.ostms.evils.ebics.service.AdminUserService adminUserService;

    /** TestRestTemplate's default JDK-based request factory follows the 302 to /login that Spring
     * Security's form-login entry point issues for an unauthenticated request, which would
     * otherwise make an unauthenticated call look like a successful 200 (the login page) instead
     * of the rejection it actually is - disable that so these tests see the real first response. */
    @BeforeEach
    void disableRedirectFollowing() {
        rest.getRestTemplate().setRequestFactory(new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(java.net.HttpURLConnection connection, String httpMethod) throws java.io.IOException {
                super.prepareConnection(connection, httpMethod);
                connection.setInstanceFollowRedirects(false);
            }
        });
    }

    @Test
    void tenantSignupIsReachableWithoutAuthentication() {
        Map<String, Object> body = Map.of(
                "hostId", "SECTEST-" + UUID.randomUUID().toString().substring(0, 8),
                "hostName", "Security Test Bank",
                "adminUsername", "sectestadmin-" + UUID.randomUUID().toString().substring(0, 8),
                "adminPassword", "supersecret123",
                "requesterEmail", "sectest@example.com");
        RequestEntity<Map<String, Object>> request = RequestEntity
                .post(URI.create("/api/tenant-signup"))
                .header("Content-Type", "application/json")
                .body(body);

        ResponseEntity<String> response = rest.exchange(request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    /** Not authenticated, not a 2xx and not the actual data - the custom LoginUrlAuthenticationEntryPoint
     * (see SecurityConfiguration, added alongside optional SUPERADMIN 2FA) rejects with either a
     * direct 401/403 or a 302 redirect to the custom /login.html page depending on content
     * negotiation; either way, this must never be a successful response carrying tenant-request
     * data to an anonymous caller. */
    private static void assertRejectedAsUnauthenticated(ResponseEntity<String> response) {
        assertThat(response.getStatusCode().is2xxSuccessful()).isFalse();
        if (response.getStatusCode() == HttpStatus.FOUND) {
            assertThat(response.getHeaders().getLocation()).isNotNull();
            assertThat(response.getHeaders().getLocation().getPath()).isEqualTo("/login.html");
        } else {
            assertThat(response.getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
        }
    }

    @Test
    void tenantRequestReviewQueueRejectsUnauthenticatedAccess() {
        assertRejectedAsUnauthenticated(rest.getForEntity("/admin/api/tenant-requests", String.class));
    }

    @Test
    void tenantsAdminEndpointRejectsUnauthenticatedAccess() {
        assertRejectedAsUnauthenticated(rest.getForEntity("/admin/api/tenants", String.class));
    }

    /** The JSESSIONID cookie is the admin session's bearer credential for all tenant banking
     * data. With CSRF protection deliberately disabled, the cookie flags (Secure, SameSite=Lax -
     * see application.yml) are what stops it leaking over plain HTTP or in a cross-site POST. */
    @Test
    void loginSessionCookieIsMarkedSecureAndSameSiteLax() {
        String username = "cookietest-" + UUID.randomUUID().toString().substring(0, 8);
        adminUserService.create(null, username, "Cookie Test", "supersecret123", "ADMIN");

        MultiValueMap<String, String> form = new org.springframework.util.LinkedMultiValueMap<>();
        form.add("username", username);
        form.add("password", "supersecret123");
        ResponseEntity<String> response = rest.postForEntity("/login", form, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        var cookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(cookies).isNotNull();
        assertThat(cookies.stream().filter(cookie -> cookie.contains("JSESSIONID")))
                .anySatisfy(cookie -> {
                    assertThat(cookie).contains("Secure");
                    assertThat(cookie).contains("SameSite=Lax");
                });
    }
}
