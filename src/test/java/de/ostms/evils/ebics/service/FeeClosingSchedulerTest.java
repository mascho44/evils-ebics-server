package de.ostms.evils.ebics.service;

import de.ostms.evils.ebics.domain.Account;
import de.ostms.evils.ebics.persistence.AccountRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class FeeClosingSchedulerTest {
    private final FeeTestDataService fees = mock(FeeTestDataService.class);
    private final AccountRepository accounts = mock(AccountRepository.class);

    private Account account(String iban) {
        Account a = mock(Account.class);
        when(a.getIban()).thenReturn(iban);
        return a;
    }

    @Test
    void doesNothingWhenDisabled() {
        new FeeClosingScheduler(fees, accounts, false, 2).run();
        verifyNoInteractions(fees, accounts);
    }

    @Test
    void closesPreviousMonthForEveryAccountAndSurvivesOneFailure() {
        List<Account> alle = List.of(account("DE01"), account("DE02"));
        when(accounts.findAll()).thenReturn(alle);
        when(fees.generate(eq("DE01"), anyInt(), anyInt())).thenThrow(new IllegalArgumentException("boom"));

        new FeeClosingScheduler(fees, accounts, true, 3).run();

        verify(fees).generate("DE01", 1, 3);
        verify(fees).generate("DE02", 1, 3);
    }
}
