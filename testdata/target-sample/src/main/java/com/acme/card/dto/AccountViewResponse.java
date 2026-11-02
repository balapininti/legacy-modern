package com.acme.card.dto;

import java.math.BigDecimal;

public record AccountViewResponse(Long acctId, String acctActiveStatus, BigDecimal acctCurrBal, BigDecimal acctCreditLimit) {}
