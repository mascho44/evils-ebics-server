package de.ostms.evils.ebics.admin;

import de.ostms.evils.ebics.domain.Bank;
import de.ostms.evils.ebics.security.TenantContext;
import de.ostms.evils.ebics.service.BankService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for a real production incident: after the AdminUser.hostId migration, the
 * two pre-existing admin logins became SUPERADMIN (hostId == null, see
 * AdminUserService.seedBootstrapUser/BankService.backfillLegacyHostId). The static admin page
 * (src/main/resources/static/index.html) calls the admin API with no X-Tenant-Host-Id header,
 * which broke it completely for those logins until this single-tenant fallback was added.
 */
class TenantResolverTest {

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void scopedAdminAlwaysUsesTenantContextRegardlessOfHeader() {
        TenantContext.set("SCOPEDBANK");
        BankService bankService = mock(BankService.class);

        assertThat(TenantResolver.effectiveHostId(null, bankService)).isEqualTo("SCOPEDBANK");
        assertThat(TenantResolver.effectiveHostId("OTHERBANK", bankService)).isEqualTo("SCOPEDBANK");
    }

    @Test
    void superadminWithoutHeaderFallsBackToTheOnlyExistingTenant() {
        BankService bankService = mock(BankService.class);
        when(bankService.banks()).thenReturn(List.of(new Bank("EVILSBANK", "EVILS Test Bank", "H005", "EVILDEFFXXX")));

        assertThat(TenantResolver.effectiveHostId(null, bankService)).isEqualTo("EVILSBANK");
        assertThat(TenantResolver.effectiveHostId("", bankService)).isEqualTo("EVILSBANK");
    }

    @Test
    void superadminWithHeaderUsesTheHeaderEvenWithOneTenant() {
        BankService bankService = mock(BankService.class);
        when(bankService.banks()).thenReturn(List.of(new Bank("EVILSBANK", "EVILS Test Bank", "H005", "EVILDEFFXXX")));

        assertThat(TenantResolver.effectiveHostId("EVILSBANK", bankService)).isEqualTo("EVILSBANK");
    }

    @Test
    void superadminWithoutHeaderAndMultipleTenantsMustSpecifyOne() {
        BankService bankService = mock(BankService.class);
        when(bankService.banks()).thenReturn(List.of(
                new Bank("EVILSBANK", "EVILS Test Bank", "H005", "EVILDEFFXXX"),
                new Bank("OTHERBANK", "Other Test Bank", "H005", "OTHRDEFFXXX")));

        assertThatThrownBy(() -> TenantResolver.effectiveHostId(null, bankService))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void superadminWithoutHeaderAndNoTenantsMustSpecifyOne() {
        BankService bankService = mock(BankService.class);
        when(bankService.banks()).thenReturn(List.of());

        assertThatThrownBy(() -> TenantResolver.effectiveHostId(null, bankService))
                .isInstanceOf(ResponseStatusException.class);
    }
}
