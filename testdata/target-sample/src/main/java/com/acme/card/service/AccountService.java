package com.acme.card.service;

import com.acme.card.dto.AccountViewResponse;
import com.acme.card.repo.AccountRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** Account view. Ported from COACTVWC. */
@Service
public class AccountService {
    private final AccountRepository accounts;

    public AccountService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    public Optional<AccountViewResponse> view(long id) {
        return accounts.findById(id).map(a -> new AccountViewResponse(a.getAcctId(), "Y", a.getAcctCurrBal(), null));
    }
}
