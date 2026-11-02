package com.modtoolkit.retrieval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Ranks by rare words: a term matching few chunks counts for far more than one matching many, and "does" counts for
 * nothing. A name the question spells out exactly, a paragraph or program, outranks anything that merely mentions it.
 * Whole identifiers go through tsvector and partial ones through pg_trgm.
 */
@Service
public class LexicalSearch {
    private static final Set<String> STOP = Set.of("a", "an", "and", "are", "as", "at", "be", "by", "can", "do", "does", "did", "for", "from", "has", "have", "how", "if", "in", "into", "is", "it", "its", "of", "on", "or",
            "that", "the", "their", "them", "then", "there", "they", "this", "to", "was", "were", "what", "when", "where", "which", "who", "why", "will", "with", "would", "it's", "yet", "no", "not", "any", "all", "each", "other", "about", "actually", "used", "using", "use", "get", "gets");
    /** How COBOL shops abbreviate. A question says account; the code says ACCT. */
    private static final Map<String, List<String>> ABBREVIATIONS = Map.ofEntries(
            Map.entry("account", List.of("acct")), Map.entry("accounts", List.of("acct")), Map.entry("customer", List.of("cust")), Map.entry("customers", List.of("cust")),
            Map.entry("transaction", List.of("tran", "trans", "trn")), Map.entry("transactions", List.of("tran", "trans", "trn")), Map.entry("balance", List.of("bal")), Map.entry("balances", List.of("bal")),
            Map.entry("amount", List.of("amt")), Map.entry("number", List.of("num", "no", "nbr")), Map.entry("address", List.of("addr")), Map.entry("message", List.of("msg")), Map.entry("messages", List.of("msg")),
            Map.entry("password", List.of("pwd", "passwd")), Map.entry("user", List.of("usr", "userid")), Map.entry("users", List.of("usr")), Map.entry("interest", List.of("int")), Map.entry("category", List.of("cat", "catg")),
            Map.entry("date", List.of("dt")), Map.entry("error", List.of("err")), Map.entry("errors", List.of("err")), Map.entry("record", List.of("rec")), Map.entry("records", List.of("rec")),
            Map.entry("screen", List.of("map", "scrn")), Map.entry("program", List.of("pgm")), Map.entry("authorization", List.of("auth")), Map.entry("authorizations", List.of("auth")), Map.entry("card", List.of("crd")),
            Map.entry("sign-on", List.of("signon")), Map.entry("signon", List.of("sign-on")), Map.entry("menu", List.of("men")), Map.entry("validate", List.of("edit", "valid")), Map.entry("validated", List.of("edit", "valid")),
            Map.entry("validation", List.of("edit", "valid")), Map.entry("check", List.of("edit", "valid")), Map.entry("checked", List.of("edit", "valid")), Map.entry("update", List.of("rewrite", "upd")), Map.entry("updated", List.of("rewrite", "upd")),
            Map.entry("delete", List.of("del")), Map.entry("total", List.of("tot")), Map.entry("report", List.of("rpt")), Map.entry("reports", List.of("rpt")), Map.entry("reference", List.of("xref")), Map.entry("cross", List.of("xref")),
            Map.entry("calculate", List.of("compute")), Map.entry("calculated", List.of("compute")), Map.entry("calculation", List.of("compute")), Map.entry("minimum", List.of("min")), Map.entry("maximum", List.of("max")), Map.entry("limit", List.of("lim")));
    private static final Pattern TOKEN = Pattern.compile("[a-z0-9]+(?:[-_][a-z0-9]+)*");
    private static final int MAX_TERMS = 16;

    private final JdbcTemplate jdbc;

    public LexicalSearch(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private record Term(String token, double weight, boolean fromQuestion) {}

    public List<SearchHit> search(String query, List<String> kinds, int limit) {
        List<Term> terms = terms(query);
        if (terms.isEmpty()) return List.of();
        String arr = kinds == null || kinds.isEmpty() ? null : "{" + String.join(",", kinds) + "}";
        StringBuilder score = new StringBuilder("(0");
        StringBuilder where = new StringBuilder("(false");
        List<Object> scoreArgs = new ArrayList<>(), whereArgs = new ArrayList<>();
        for (Term t : terms) {
            score.append(" + CASE WHEN c.tsv @@ cobol_tsquery(?) THEN ? ELSE 0 END");
            scoreArgs.add(t.token());
            scoreArgs.add(t.weight());
            where.append(" OR c.tsv @@ cobol_tsquery(?)");
            whereArgs.add(t.token());
            // The question names this unit, or its program: nothing merely mentioning it should come first.
            score.append(" + CASE WHEN lower(coalesce(c.metadata->>'unit','')) = ? OR lower(coalesce(c.metadata->>'unit','')) LIKE ? THEN ? ELSE 0 END");
            scoreArgs.add(t.token());
            scoreArgs.add("%." + t.token());
            scoreArgs.add(t.weight() * 4);
            score.append(" + CASE WHEN lower(coalesce(m.name,'')) = ? THEN ? ELSE 0 END");
            scoreArgs.add(t.token());
            scoreArgs.add(t.weight() * 1.5);
        }
        score.append(")");
        where.append(")");
        // A partial identifier: only for what looks like one, since a substring of an ordinary word is everywhere.
        for (String part : partialIdentifiers(query, terms)) {
            where.append(" OR c.text ILIKE ?");
            whereArgs.add("%" + escapeLike(part) + "%");
            score.append(" + CASE WHEN c.text ILIKE ? THEN 1.0 ELSE 0 END");
            scoreArgs.add("%" + escapeLike(part) + "%");
        }
        List<Object> args = new ArrayList<>(scoreArgs);
        args.addAll(whereArgs);
        if (arr != null) args.add(arr);
        args.add(limit);
        // Long units match more by sheer size, so they pay for it; reviewed knowledge is worth more than a draft.
        String sql = "SELECT c.kind, coalesce(m.path, c.metadata->>'path'), c.line_start, c.line_end, c.text, c.artifact_id, a.status, c.metadata->>'unit' AS unit, "
                + score + " / (1 + 0.12 * ln(1 + coalesce(c.line_end - c.line_start, 0))) * (CASE WHEN a.status = 'reviewed' THEN 2 WHEN a.status = 'rejected' THEN 0.1 ELSE 1 END) AS score "
                + "FROM chunk c LEFT JOIN member m ON m.id = c.member_id LEFT JOIN knowledge_artifact a ON a.id = c.artifact_id "
                + "WHERE " + where + " AND coalesce(a.status, '') <> 'rejected'" + (arr == null ? "" : " AND c.kind = ANY(?::text[])")
                + " ORDER BY score DESC, 2, c.line_start LIMIT ?";
        String needle = terms.get(0).token();
        return jdbc.query(sql, (rs, i) -> {
            Integer start = (Integer) rs.getObject(3);
            return new SearchHit(rs.getString(1), rs.getString(2), start, (Integer) rs.getObject(4), snippet(rs.getString(5), needle), rs.getDouble(9), (Long) rs.getObject(6), rs.getString(7), rs.getString(8));
        }, args.toArray());
    }

    /** Rarest first, weighted by inverse document frequency. A term present nearly everywhere says nothing. */
    private List<Term> terms(String query) {
        Long total = jdbc.queryForObject("SELECT count(*) FROM chunk", Long.class);
        long n = Math.max(1, total == null ? 1 : total);
        Map<String, Boolean> tokens = new LinkedHashMap<>();
        Matcher m = TOKEN.matcher(query.toLowerCase(Locale.ROOT));
        while (m.find()) {
            String t = m.group();
            if (STOP.contains(t)) continue;
            tokens.put(t, true);
            for (String a : ABBREVIATIONS.getOrDefault(t, List.of())) tokens.putIfAbsent(a, false);
        }
        List<Term> out = new ArrayList<>();
        for (Map.Entry<String, Boolean> e : tokens.entrySet()) {
            Long df = jdbc.queryForObject("SELECT count(*) FROM chunk c WHERE c.tsv @@ cobol_tsquery(?)", Long.class, e.getKey());
            long d = df == null ? 0 : df;
            if (d == 0) continue;
            double idf = Math.log(1.0 + (double) n / d);
            // An identifier the question spelled out is what it is asking about.
            double w = idf * (e.getKey().contains("-") ? 3 : 1) * (e.getValue() ? 1 : 0.6);
            out.add(new Term(e.getKey(), Math.round(w * 1000) / 1000.0, e.getValue()));
        }
        out.sort((a, b) -> Double.compare(b.weight(), a.weight()));
        return out.size() > MAX_TERMS ? out.subList(0, MAX_TERMS) : out;
    }

    private List<String> partialIdentifiers(String query, List<Term> found) {
        List<String> out = new ArrayList<>();
        Matcher m = TOKEN.matcher(query.toLowerCase(Locale.ROOT));
        while (m.find()) {
            String t = m.group();
            boolean known = found.stream().anyMatch(x -> x.token().equals(t));
            if (!known && t.length() >= 3 && !STOP.contains(t) && (t.contains("-") || t.matches(".*\\d.*"))) out.add(t);
        }
        // A single word the index does not know may still be part of an identifier.
        if (found.isEmpty()) {
            Matcher w = TOKEN.matcher(query.toLowerCase(Locale.ROOT));
            while (w.find()) if (w.group().length() >= 3 && !STOP.contains(w.group()) && !out.contains(w.group())) out.add(w.group());
        }
        return out;
    }

    private static String escapeLike(String s) {
        return s.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** The matching line with a line either side, or the start of the unit. */
    static String snippet(String text, String needle) {
        String[] lines = text.split("\n", -1);
        int at = 0;
        for (int i = 0; i < lines.length && !needle.isEmpty(); i++) {
            if (lines[i].toLowerCase(Locale.ROOT).contains(needle)) {
                at = i;
                break;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, at - 1); i <= Math.min(lines.length - 1, at + 1); i++) sb.append(lines[i].stripTrailing()).append('\n');
        String s = sb.toString().stripTrailing();
        return s.length() > 400 ? s.substring(0, 400) : s;
    }
}
