package de.ostms.evils.ebics.service;

import de.ostms.evils.ebics.domain.AdminUser;
import de.ostms.evils.ebics.persistence.AdminUserRepository;
import de.ostms.evils.ebics.security.CredentialProvider;
import de.ostms.evils.ebics.security.EvilsUserDetails;
import de.ostms.evils.ebics.security.TotpService;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

/**
 * Admin users of the web console and the Spring Security {@link UserDetailsService} that loads them.
 * Passwords are stored as BCrypt hashes. Users belong to one tenant (host ID); the super admin can manage
 * all. Also implements the self-service TOTP two-factor setup (begin, confirm with a code, disable).
 */
@Service
public class AdminUserService implements UserDetailsService {
    private final AdminUserRepository users; private final PasswordEncoder encoder; private final String bootstrapUsername; private final String bootstrapPassword; private final TotpService totp;
    public AdminUserService(AdminUserRepository users, PasswordEncoder encoder, CredentialProvider credentials, TotpService totp) { this.users=users; this.encoder=encoder; this.bootstrapUsername=credentials.bootstrapUsername(); this.bootstrapPassword=credentials.bootstrapPassword(); this.totp=totp; }

    /** The bootstrap admin becomes SUPERADMIN (hostId=null): can both manage the default
     * "EVILSBANK" tenant seeded by BankService.seedDemoData and create further tenants (see
     * TenantController) - preserves today's single-command deployment with no new required env
     * vars while unlocking multi-tenancy for operators who want it. Refuses to seed with the
     * insecure default password: an empty admin_users table means this is a FIRST start, and a
     * first start that silently creates a SUPERADMIN with a well-known password is exactly the
     * "one env var forgotten" deployment that must fail loudly instead (same principle as
     * BankKeyService refusing to generate bank keys without a real master secret). Existing
     * installations (users present) are unaffected. */
    private static final String DEFAULT_BOOTSTRAP_PASSWORD = "changeit";
    @Transactional public void seedBootstrapUser() {
        String username=bootstrapUsername, password=bootstrapPassword;
        if (users.count() == 0) {
            if (DEFAULT_BOOTSTRAP_PASSWORD.equals(password)) throw new IllegalStateException(
                    "EVILS_ADMIN_BOOTSTRAP_PASSWORD is still set to the default \"" + DEFAULT_BOOTSTRAP_PASSWORD
                            + "\" - set a real password before the first start (the bootstrap account is SUPERADMIN).");
            users.save(new AdminUser(null, username, "Local Administrator", encoder.encode(password), "ADMIN", true));
        }
    }
    public List<AdminUser> all() { return users.findAll(); }
    public List<AdminUser> allForTenant(String hostId) { return users.findAllByHostId(hostId); }
    public AdminUser byUsername(String username) {
        return users.findById(username).orElseThrow(() -> new IllegalArgumentException("Unknown admin user: " + username));
    }

    /** hostId is null only for creating another SUPERADMIN (TenantController); every other caller
     * (AdminUserController, scoped to the acting admin's own tenant) passes their own hostId. */
    @Transactional public AdminUser create(String hostId, String username, String displayName, String password, String role) {
        if (password == null || password.length() < 8) throw new IllegalArgumentException("username and a password with at least 8 characters are required");
        return createWithHash(hostId, username, displayName, encoder.encode(password), role);
    }

    /** Same as create(), but takes an already-hashed password instead of hashing a raw one - for
     * TenantRequestService.approve, whose TenantRequest hashed the password at submission time
     * (never storing it in plaintext, even temporarily) and must not hash it a second time here. */
    @Transactional public AdminUser createWithHash(String hostId, String username, String displayName, String passwordHash, String role) {
        if (username == null || username.isBlank() || passwordHash == null || passwordHash.isBlank()) throw new IllegalArgumentException("username and a password hash are required");
        if (users.existsById(username)) throw new IllegalArgumentException("Username already exists");
        String normalizedRole = "OPERATOR".equalsIgnoreCase(role) ? "OPERATOR" : "ADMIN";
        return users.save(new AdminUser(hostId, username, displayName == null || displayName.isBlank() ? username : displayName, passwordHash, normalizedRole, true));
    }
    /** User management acts on a bare username, so without this check a tenant-scoped admin could
     * target users of ANOTHER tenant (or a SUPERADMIN) by simply guessing/knowing their username -
     * including resetting their password to a known value. A SUPERADMIN (actingHostId == null) may
     * manage anyone; a tenant-scoped admin only users of their own tenant. */
    private static void requireTargetInActingScope(AdminUser target, String actingHostId) {
        if (actingHostId != null && !actingHostId.equals(target.getHostId()))
            throw new IllegalStateException("Benutzer eines anderen Mandanten können nicht verwaltet werden.");
    }
    @Transactional public void changePassword(String username, String password, String actingUsername, String actingHostId) {
        if (password == null || password.length() < 8) throw new IllegalArgumentException("Das Passwort muss mindestens 8 Zeichen lang sein.");
        AdminUser user=users.findById(username).orElseThrow(() -> new IllegalArgumentException("Unknown admin user: " + username));
        requireTargetInActingScope(user, actingHostId);
        user.setPasswordHash(encoder.encode(password));
    }
    @Transactional public AdminUser setActive(String username, boolean active, String actingUsername, String actingHostId) {
        AdminUser user=users.findById(username).orElseThrow(() -> new IllegalArgumentException("Unknown admin user: " + username));
        requireTargetInActingScope(user, actingHostId);
        if (!active && username.equals(actingUsername)) throw new IllegalStateException("Das eigene Admin-Konto kann nicht deaktiviert werden.");
        if (!active && "ADMIN".equals(user.getRole()) && user.isActive() && activeAdminCount(user.getHostId()) <= 1) {
            throw new IllegalStateException("Der letzte aktive Administrator kann nicht deaktiviert werden.");
        }
        user.setActive(active);
        return user;
    }
    @Transactional public void delete(String username, String actingUsername, String actingHostId) {
        AdminUser user=users.findById(username).orElseThrow(() -> new IllegalArgumentException("Unknown admin user: " + username));
        requireTargetInActingScope(user, actingHostId);
        if (username.equals(actingUsername)) throw new IllegalStateException("Das eigene Admin-Konto kann nicht gelöscht werden.");
        if ("ADMIN".equals(user.getRole()) && user.isActive() && activeAdminCount(user.getHostId()) <= 1) {
            throw new IllegalStateException("Der letzte aktive Administrator kann nicht gelöscht werden.");
        }
        users.delete(user);
    }

    /** "Last active ADMIN" is scoped per tenant for a normal admin, and per the SUPERADMIN pool
     * (hostId == null) for a superadmin - see AdminUserRepository.countByHostIdIsNullAndRoleAndActiveTrue
     * for why hostId == null needs a separate query. */
    private long activeAdminCount(String hostId) {
        return hostId == null ? users.countByHostIdIsNullAndRoleAndActiveTrue("ADMIN")
                : users.countByHostIdAndRoleAndActiveTrue(hostId, "ADMIN");
    }

    @Override public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        AdminUser user=users.findById(username).orElseThrow(() -> new UsernameNotFoundException(username));
        return new EvilsUserDetails(user.getUsername(), user.passwordHash(), user.getRole(), user.isActive(), user.getHostId(),
                user.isTotpEnabled(), user.totpSecret());
    }

    public record TotpSetup(String secret, String otpauthUri) { }

    /** Generates a fresh secret and stores it, but leaves totpEnabled false until the user proves
     * they actually set it up correctly via confirmTotpSetup - otherwise a setup request that's
     * never completed (e.g. the user navigated away) would silently overwrite an already-working
     * secret with one nobody's authenticator app knows, locking the account out at next login. */
    @Transactional public TotpSetup beginTotpSetup(String username) {
        AdminUser user = users.findById(username).orElseThrow(() -> new IllegalArgumentException("Unknown admin user: " + username));
        String secret = totp.generateSecret();
        user.setTotpSecret(secret);
        return new TotpSetup(secret, totp.otpauthUri(secret, username));
    }

    /** Confirms a pending beginTotpSetup() by requiring one valid code first - proves the secret
     * was actually scanned/entered correctly before 2FA becomes mandatory at login. */
    @Transactional public void confirmTotpSetup(String username, String code) {
        AdminUser user = users.findById(username).orElseThrow(() -> new IllegalArgumentException("Unknown admin user: " + username));
        if (user.totpSecret() == null) throw new IllegalStateException("2FA-Einrichtung wurde noch nicht gestartet.");
        if (!totp.verify(user.totpSecret(), code)) throw new IllegalArgumentException("Der eingegebene Code ist falsch oder abgelaufen.");
        user.setTotpEnabled(true);
    }

    @Transactional public void disableTotp(String username) {
        AdminUser user = users.findById(username).orElseThrow(() -> new IllegalArgumentException("Unknown admin user: " + username));
        user.setTotpEnabled(false);
        user.setTotpSecret(null);
    }
}
