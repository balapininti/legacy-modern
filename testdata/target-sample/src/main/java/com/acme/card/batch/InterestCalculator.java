package com.acme.card.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reference implementation of CBACT04C: monthly interest on each transaction category balance, posted to the account
 * and written as a transaction. Written from the COBOL source for the M7 acceptance.
 */
public final class InterestCalculator {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final BigDecimal TWELVE_HUNDRED = new BigDecimal(1200);
    private static final DateTimeFormatter DB2_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH.mm.ss.SS0000");

    /** Datasets in, datasets out, each a list of records keyed by COBOL field name. */
    public JsonNode run(String parmDate, JsonNode datasets) {
        Map<String, ObjectNode> accounts = new LinkedHashMap<>();
        for (JsonNode a : datasets.get("ACCTFILE")) accounts.put(a.get("ACCT-ID").asText(), a.deepCopy());
        Map<String, JsonNode> xrefByAccount = new HashMap<>();
        for (JsonNode x : datasets.get("XREFFILE")) xrefByAccount.putIfAbsent(x.get("XREF-ACCT-ID").asText(), x);
        Map<String, BigDecimal> rates = new HashMap<>();
        for (JsonNode d : datasets.get("DISCGRP")) {
            rates.put(key(d.get("DIS-ACCT-GROUP-ID").asText(), d.get("DIS-TRAN-TYPE-CD").asText(), d.get("DIS-TRAN-CAT-CD").asText()), new BigDecimal(d.get("DIS-INT-RATE").asText()));
        }
        ArrayNode transactions = JSON.createArrayNode();
        int suffix = 0;
        String lastAccount = null;
        BigDecimal totalInterest = BigDecimal.ZERO;
        for (JsonNode cat : datasets.get("TCATBALF")) {
            String acct = cat.get("TRANCAT-ACCT-ID").asText();
            if (!acct.equals(lastAccount)) {
                if (lastAccount != null) post(accounts.get(lastAccount), totalInterest);
                totalInterest = BigDecimal.ZERO;
                lastAccount = acct;
            }
            ObjectNode account = accounts.get(acct);
            BigDecimal rate = rate(rates, account.get("ACCT-GROUP-ID").asText(), cat);
            if (rate.signum() != 0) {
                BigDecimal monthly = new BigDecimal(cat.get("TRAN-CAT-BAL").asText()).multiply(rate).divide(TWELVE_HUNDRED, 2, RoundingMode.DOWN);
                totalInterest = totalInterest.add(monthly);
                transactions.add(transaction(parmDate, ++suffix, account, xrefByAccount.get(acct), monthly));
            }
        }
        // Faithful to the mainframe: the last account's interest is never posted. In CBACT04C.cbl the ELSE at line 220 is
        // unreachable, because the PERFORM UNTIL at line 188 exits as soon as the read sets END-OF-FILE. The transactions
        // for that account are still written. Fixing this is a business decision, to be registered as a deviation.
        ObjectNode out = JSON.createObjectNode();
        ArrayNode acctOut = out.putArray("ACCTFILE");
        accounts.values().forEach(acctOut::add);
        out.set("TRANSACT", transactions);
        return out;
    }

    /** Falls back to the DEFAULT disclosure group when the account's own group has no row, as 1200-A does. */
    private static BigDecimal rate(Map<String, BigDecimal> rates, String group, JsonNode cat) {
        String type = cat.get("TRANCAT-TYPE-CD").asText(), code = cat.get("TRANCAT-CD").asText();
        BigDecimal r = rates.get(key(group, type, code));
        if (r == null) r = rates.get(key("DEFAULT", type, code));
        if (r == null) throw new IllegalStateException("no disclosure group for " + group + "/" + type + "/" + code);
        return r;
    }

    private static String key(String group, String type, String code) {
        return group.strip() + "|" + type.strip() + "|" + new BigDecimal(code).toPlainString();
    }

    private static void post(ObjectNode account, BigDecimal interest) {
        account.put("ACCT-CURR-BAL", new BigDecimal(account.get("ACCT-CURR-BAL").asText()).add(interest).toPlainString());
        account.put("ACCT-CURR-CYC-CREDIT", "0.00");
        account.put("ACCT-CURR-CYC-DEBIT", "0.00");
    }

    private static ObjectNode transaction(String parmDate, int suffix, ObjectNode account, JsonNode xref, BigDecimal monthly) {
        ObjectNode t = JSON.createObjectNode();
        String now = LocalDateTime.now().format(DB2_TS);
        t.put("TRAN-ID", parmDate + String.format("%06d", suffix));
        t.put("TRAN-TYPE-CD", "01");
        t.put("TRAN-CAT-CD", "5");
        t.put("TRAN-SOURCE", "System");
        t.put("TRAN-DESC", "Int. for a/c " + String.format("%011d", Long.parseLong(account.get("ACCT-ID").asText())));
        t.put("TRAN-AMT", monthly.toPlainString());
        t.put("TRAN-MERCHANT-ID", "0");
        t.put("TRAN-MERCHANT-NAME", "");
        t.put("TRAN-MERCHANT-CITY", "");
        t.put("TRAN-MERCHANT-ZIP", "");
        t.put("TRAN-CARD-NUM", xref.get("XREF-CARD-NUM").asText());
        t.put("TRAN-ORIG-TS", now);
        t.put("TRAN-PROC-TS", now);
        return t;
    }
}
