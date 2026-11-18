package de.ostms.evils.ebics.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tenant scoping of admin-user management (see AdminUserService.requireTargetInActingScope):
 * changePassword/setActive/delete act on a bare username, so without the scope check a
 * tenant-scoped admin could target ANY user by username - including another tenant's admin or a
 * SUPERADMIN, whose password they could reset to a known value and then log in with. A SUPERADMIN
 * (actingHostId == null) stays able to manage everyone. */
@SpringBootTest
@ActiveProfiles("test")
class AdminUserServiceScopingTest {

    @Autowired AdminUserService service;
    @Autowired BankService banks;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder encoder;

    private String uniqueHostId() {
        return "SCOPEBANK-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** A tenant-scoped admin and one user each in two fresh tenants. Returns [hostA, adminA, userB]. */
    private String[] twoTenantsWithUsers() {
        String hostA = uniqueHostId(), hostB = uniqueHostId();
        banks.createBank(hostA, "Scope Bank A", "H005", "SCOPEDEFF");
        banks.createBank(hostB, "Scope Bank B", "H005", "SCOPEDEFF");
        String adminA = "scope-a-" + UUID.randomUUID().toString().substring(0, 8);
        String userB = "scope-b-" + UUID.randomUUID().toString().substring(0, 8);
        service.create(hostA, adminA, "Admin A", "password-a-123", "ADMIN");
        service.create(hostB, userB, "User B", "password-b-123", "ADMIN");
        return new String[]{hostA, adminA, userB};
    }

    @Test
    void tenantAdminCannotChangeAnotherTenantsUsersPassword() {
        String[] setup = twoTenantsWithUsers();
        String hostA = setup[0], adminA = setup[1], userB = setup[2];
        String hashBefore = service.byUsername(userB).passwordHash();

        assertThatThrownBy(() -> service.changePassword(userB, "new-known-pw-123", adminA, hostA))
                .isInstanceOf(IllegalStateException.class);
        // Password must really be unchanged - a thrown exception alone doesn't prove nothing was written.
        assertThat(service.byUsername(userB).passwordHash()).isEqualTo(hashBefore);
    }

    @Test
    void tenantAdminCannotDeactivateOrDeleteAnotherTenantsUser() {
        String[] setup = twoTenantsWithUsers();
        String hostA = setup[0], adminA = setup[1], userB = setup[2];

        assertThatThrownBy(() -> service.setActive(userB, false, adminA, hostA))
                .isInstanceOf(IllegalStateException.class);
        assertThat(service.byUsername(userB).isActive()).isTrue();

        assertThatThrownBy(() -> service.delete(userB, adminA, hostA))
                .isInstanceOf(IllegalStateException.class);
        assertThat(service.byUsername(userB)).isNotNull();
    }

    @Test
    void tenantAdminCannotTouchASuperadmin() {
        String[] setup = twoTenantsWithUsers();
        String hostA = setup[0], adminA = setup[1];
        String superadmin = "scope-super-" + UUID.randomUUID().toString().substring(0, 8);
        service.create(null, superadmin, "Super", "password-s-123", "ADMIN");

        assertThatThrownBy(() -> service.changePassword(superadmin, "hijacked-pw-123", adminA, hostA))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.delete(superadmin, adminA, hostA))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void superadminMayManageAnyTenant() {
        String[] setup = twoTenantsWithUsers();
        String userB = setup[2];

        service.changePassword(userB, "superadmin-set-pw", "superadmin-actor", null);

        assertThat(encoder.matches("superadmin-set-pw", service.byUsername(userB).passwordHash())).isTrue();
    }

    @Test
    void sameTenantManagementStillWorks() {
        String[] setup = twoTenantsWithUsers();
        String hostA = setup[0], adminA = setup[1];
        String colleague = "scope-a2-" + UUID.randomUUID().toString().substring(0, 8);
        service.create(hostA, colleague, "Colleague", "password-c-123", "OPERATOR");

        service.changePassword(colleague, "new-same-tenant-1", adminA, hostA);
        service.setActive(colleague, false, adminA, hostA);
        service.delete(colleague, adminA, hostA);

        assertThat(service.allForTenant(hostA).stream().noneMatch(u -> u.getUsername().equals(colleague))).isTrue();
    }
}
