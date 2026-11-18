package de.ostms.evils.ebics.service;

import de.ostms.evils.ebics.persistence.AdminUserRepository;
import de.ostms.evils.ebics.security.CredentialProvider;
import de.ostms.evils.ebics.security.TotpService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The bootstrap account is SUPERADMIN, so seeding it with the well-known default password
 * "changeit" on a first start would hand the whole instance to anyone who finds the URL -
 * see AdminUserService.seedBootstrapUser. Uses its own in-memory database (not the one shared
 * by the other Spring tests) because the interesting case needs an empty admin_users table,
 * which this test creates with deleteAll(). */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:evils-bootstrap-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE")
@ActiveProfiles("test")
class AdminUserServiceBootstrapTest {

    @Autowired AdminUserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired TotpService totp;

    private AdminUserService serviceWith(String bootstrapUsername, String bootstrapPassword) {
        CredentialProvider credentials = new CredentialProvider() {
            @Override public String masterSecret() { return "test-only-master-secret-not-for-production"; }
            @Override public String bootstrapUsername() { return bootstrapUsername; }
            @Override public String bootstrapPassword() { return bootstrapPassword; }
        };
        return new AdminUserService(users, encoder, credentials, totp);
    }

    @BeforeEach
    void emptyAdminUsers() {
        users.deleteAll();
    }

    @Test
    void refusesToSeedTheSuperadminWithTheDefaultPasswordOnFirstStart() {
        assertThatThrownBy(() -> serviceWith("admin", "changeit").seedBootstrapUser())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EVILS_ADMIN_BOOTSTRAP_PASSWORD");
        assertThat(users.count()).isZero();
    }

    @Test
    void seedsTheSuperadminWithAConfiguredPassword() {
        serviceWith("admin", "a-real-first-password").seedBootstrapUser();

        assertThat(users.count()).isEqualTo(1);
        var seeded = users.findById("admin").orElseThrow();
        assertThat(seeded.isSuperadmin()).isTrue();
        assertThat(encoder.matches("a-real-first-password", seeded.passwordHash())).isTrue();
    }

    /** Existing installations must not be broken by the new guard: with users already present,
     * seeding is (and always was) a no-op, regardless of the configured password. */
    @Test
    void isANoOpWhenUsersAlreadyExistEvenWithTheDefaultPassword() {
        serviceWith("admin", "a-real-first-password").seedBootstrapUser();
        long before = users.count();

        serviceWith("admin", "changeit").seedBootstrapUser();

        assertThat(users.count()).isEqualTo(before);
    }
}
