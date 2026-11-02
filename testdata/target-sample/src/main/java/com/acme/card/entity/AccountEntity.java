package com.acme.card.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/** Replaces the ACCTFILE KSDS, record layout CVACT01Y. */
@Entity
@Table(name = "ACCOUNT")
public class AccountEntity {
    @Id
    @Column(name = "ACCT_ID")
    private Long acctId;

    @Column(name = "ACCT_CURR_BAL")
    private BigDecimal acctCurrBal;

    public Long getAcctId() {
        return acctId;
    }

    public BigDecimal getAcctCurrBal() {
        return acctCurrBal;
    }
}
