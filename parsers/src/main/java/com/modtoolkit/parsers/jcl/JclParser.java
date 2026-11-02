package com.modtoolkit.parsers.jcl;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads jobs and expands PROCs with symbolic substitution. Each fact keeps the member and line it was written on. */
public final class JclParser {
    public record Dd(String name, String dsn, String disp, Integer gdgRel, Map<String, String> attrs, String member, int line) {}
    public record Step(int seq, String name, String pgm, String proc, String cond, String ifCond, Map<String, String> params, List<Dd> dds, String member, int line) {}
    public record Job(String name, Map<String, String> params, int line, List<Step> steps, List<String> missingProcs, Map<String, String> symbols, List<String> controlText) {}
    public record ProcBody(String member, List<String> lines) {}

    private record Stmt(String name, String op, String params, int line, List<String> inline) {}

    private static final Pattern SYMBOL = Pattern.compile("&([A-Za-z@#$][A-Za-z0-9@#$]{0,7})\\.?");
    private static final Pattern DB2_RUN = Pattern.compile("(?i)\\bRUN\\s+PROGRAM\\s*\\(\\s*([A-Z0-9@#$]+)\\s*\\)");
    private static final Pattern PROC_LIKE_PGM = Pattern.compile("(?i)^(IKJEFT01|IKJEFT1A|IKJEFT1B)$");
    private static final Pattern IMS_PGM = Pattern.compile("(?i)^(DFSRRC00|DLIBATCH)$");
    private static final int MAX_PROC_DEPTH = 15;

    private final Function<String, ProcBody> procLibrary;
    private final String member;
    private final Map<String, List<Stmt>> instream = new HashMap<>();

    private JclParser(String member, Function<String, ProcBody> procLibrary) {
        this.member = member;
        this.procLibrary = procLibrary;
    }

    public static List<Job> parse(String member, List<String> lines, Function<String, ProcBody> procLibrary) {
        return new JclParser(member, procLibrary).jobs(lines);
    }

    private List<Job> jobs(List<String> lines) {
        List<Stmt> stmts = statements(lines);
        List<Job> out = new ArrayList<>();
        int i = 0;
        // In-stream PROC ... PEND blocks may sit anywhere before their use.
        List<Stmt> main = new ArrayList<>();
        while (i < stmts.size()) {
            Stmt s = stmts.get(i);
            if (s.op().equals("PROC") && s.name() != null) {
                List<Stmt> body = new ArrayList<>();
                body.add(s);
                i++;
                while (i < stmts.size() && !stmts.get(i).op().equals("PEND")) body.add(stmts.get(i++));
                i++;
                instream.put(s.name().toUpperCase(Locale.ROOT), body);
                continue;
            }
            main.add(s);
            i++;
        }
        Job current = null;
        Map<String, String> symbols = new LinkedHashMap<>();
        List<Stmt> buf = new ArrayList<>();
        for (Stmt s : main) {
            if (s.op().equals("JOB")) {
                if (current != null) out.add(finish(current, buf, symbols));
                buf = new ArrayList<>();
                symbols = new LinkedHashMap<>();
                current = new Job(s.name(), params(s.params()), s.line(), new ArrayList<>(), new ArrayList<>(), symbols, new ArrayList<>());
                continue;
            }
            buf.add(s);
        }
        if (current != null) out.add(finish(current, buf, symbols));
        return out;
    }

    private Job finish(Job job, List<Stmt> stmts, Map<String, String> symbols) {
        int[] seq = {0};
        walk(stmts, symbols, member, job, seq, new ArrayDeque<>(), new ArrayDeque<>(), null);
        return job;
    }

    private void walk(List<Stmt> stmts, Map<String, String> symbols, String source, Job job, int[] seq, Deque<String> procStack, Deque<String> ifs, Map<String, List<Dd>> overrides) {
        Step open = null;
        List<Dd> dds = null;
        String lastDdName = null;
        for (int i = 0; i < stmts.size(); i++) {
            Stmt s = stmts.get(i);
            switch (s.op()) {
                case "SET" -> params(s.params()).forEach((k, v) -> symbols.put(k, substitute(unquote(v), symbols)));
                case "IF" -> ifs.push(s.params().replaceAll("\\s+THEN\\s*$", "").strip());
                case "ELSE" -> { if (!ifs.isEmpty()) ifs.push("NOT (" + ifs.pop() + ")"); }
                case "ENDIF" -> { if (!ifs.isEmpty()) ifs.pop(); }
                case "EXEC" -> {
                    if (open != null) job.steps().add(open);
                    open = null;
                    dds = new ArrayList<>();
                    lastDdName = null;
                    Map<String, String> p = params(substitute(s.params(), symbols));
                    String pgm = p.get("PGM");
                    String proc = p.get("PROC");
                    String first = firstPositional(s.params());
                    if (pgm == null && proc == null && first != null) proc = substitute(first, symbols);
                    String cond = p.get("COND");
                    String ifCond = ifs.isEmpty() ? null : String.join(" AND ", ifs);
                    if (pgm != null) {
                        open = new Step(++seq[0], s.name(), unquote(pgm).toUpperCase(Locale.ROOT), null, cond, ifCond, p, dds, source, s.line());
                    } else if (proc != null) {
                        // DD overrides for the PROC's steps follow its EXEC; gather them before expanding.
                        Map<String, List<Dd>> ov = new HashMap<>();
                        int j = i + 1;
                        while (j < stmts.size() && stmts.get(j).op().equals("DD")) {
                            Stmt d = stmts.get(j);
                            String nm = d.name() == null ? "" : d.name();
                            int dot = nm.indexOf('.');
                            if (dot > 0) ov.computeIfAbsent(nm.substring(0, dot).toUpperCase(Locale.ROOT), k -> new ArrayList<>()).add(dd(d, nm.substring(dot + 1), symbols, source));
                            j++;
                        }
                        expand(proc.toUpperCase(Locale.ROOT), p, symbols, s, job, seq, procStack, ifs, ov);
                        i = j - 1;
                    }
                }
                case "DD" -> {
                    if (open == null || dds == null) break;
                    String nm = s.name();
                    if (nm == null) nm = lastDdName; else lastDdName = nm; // A concatenation continues the previous DD.
                    Dd d = dd(s, nm, symbols, source);
                    dds.add(d);
                    if (!s.inline().isEmpty()) {
                        String text = String.join("\n", s.inline());
                        job.controlText().add(text);
                        open = withInlineTarget(open, text);
                    }
                }
                default -> { }
            }
        }
        if (open != null) {
            if (overrides != null && open.name() != null) applyOverrides(open, overrides);
            job.steps().add(open);
        }
        if (overrides != null) {
            for (int k = 0; k < job.steps().size(); k++) {
                Step st = job.steps().get(k);
                if (st.name() != null && overrides.containsKey(st.name().toUpperCase(Locale.ROOT))) applyOverrides(st, overrides);
            }
        }
    }

    private static void applyOverrides(Step st, Map<String, List<Dd>> overrides) {
        List<Dd> ov = overrides.remove(st.name().toUpperCase(Locale.ROOT));
        if (ov == null) return;
        for (Dd o : ov) {
            boolean replaced = false;
            for (int i = 0; i < st.dds().size(); i++) {
                if (st.dds().get(i).name() != null && st.dds().get(i).name().equalsIgnoreCase(o.name())) {
                    Dd base = st.dds().get(i);
                    Map<String, String> a = new LinkedHashMap<>(base.attrs());
                    a.putAll(o.attrs());
                    a.put("override", "true");
                    st.dds().set(i, new Dd(o.name(), o.dsn() != null ? o.dsn() : base.dsn(), o.disp() != null ? o.disp() : base.disp(), o.dsn() != null ? o.gdgRel() : base.gdgRel(), a, o.member(), o.line()));
                    replaced = true;
                    break;
                }
            }
            if (!replaced) st.dds().add(o);
        }
    }

    /** The real program of a DB2 batch step is named in its SYSTSIN, not on the EXEC. */
    private static Step withInlineTarget(Step st, String text) {
        if (st.pgm() != null && PROC_LIKE_PGM.matcher(st.pgm()).matches()) {
            Matcher m = DB2_RUN.matcher(text);
            if (m.find()) {
                Map<String, String> p = new LinkedHashMap<>(st.params());
                p.put("RUN_VIA", st.pgm());
                return new Step(st.seq(), st.name(), m.group(1).toUpperCase(Locale.ROOT), st.proc(), st.cond(), st.ifCond(), p, st.dds(), st.member(), st.line());
            }
        }
        return st;
    }

    private void expand(String proc, Map<String, String> execParams, Map<String, String> callerSymbols, Stmt call, Job job, int[] seq, Deque<String> stack, Deque<String> ifs, Map<String, List<Dd>> overrides) {
        if (stack.contains(proc) || stack.size() >= MAX_PROC_DEPTH) {
            job.missingProcs().add(proc + " (recursive)");
            return;
        }
        List<Stmt> body;
        String source;
        if (instream.containsKey(proc)) {
            body = instream.get(proc);
            source = member;
        } else {
            ProcBody lib = procLibrary.apply(proc);
            if (lib == null) {
                job.missingProcs().add(proc);
                // The step still exists; only its contents are unknown.
                Map<String, String> p = new LinkedHashMap<>(execParams);
                job.steps().add(new Step(++seq[0], call.name(), null, proc, execParams.get("COND"), null, p, new ArrayList<>(), member, call.line()));
                return;
            }
            source = lib.member();
            body = new JclParser(source, procLibrary).statements(lib.lines());
        }
        // Precedence: EXEC overrides beat the PROC's defaults; the caller's symbols are visible inside too.
        Map<String, String> sym = new LinkedHashMap<>(callerSymbols);
        for (Stmt s : body) {
            if (s.op().equals("PROC")) params(s.params()).forEach((k, v) -> sym.put(k, substitute(unquote(v), callerSymbols)));
        }
        execParams.forEach((k, v) -> {
            if (!k.equals("PROC") && !k.equals("COND") && !k.equals("PARM")) sym.put(k, unquote(v));
        });
        for (Map.Entry<String, String> e : sym.entrySet()) {
            for (int depth = 0; depth < 10 && e.getValue().contains("&"); depth++) {
                String next = substitute(e.getValue(), sym);
                if (next.equals(e.getValue())) break;
                e.setValue(next);
            }
        }
        List<Stmt> steps = body.stream().filter(s -> !s.op().equals("PROC") && !s.op().equals("PEND")).toList();
        stack.push(proc);
        int before = job.steps().size();
        walk(steps, sym, source, job, seq, stack, ifs, overrides);
        stack.pop();
        // Mark the steps the PROC produced with its name and the symbols it used.
        for (int i = before; i < job.steps().size(); i++) {
            Step st = job.steps().get(i);
            Map<String, String> p = new LinkedHashMap<>(st.params());
            p.put("PROCSTEP", call.name() == null ? "" : call.name());
            job.steps().set(i, new Step(st.seq(), st.name(), st.pgm(), st.proc() != null ? st.proc() : proc, st.cond(), st.ifCond(), p, st.dds(), st.member(), st.line()));
        }
    }

    private Dd dd(Stmt s, String name, Map<String, String> symbols, String source) {
        Map<String, String> p = params(substitute(s.params(), symbols));
        String dsn = p.containsKey("DSN") ? p.get("DSN") : p.get("DSNAME");
        Integer rel = null;
        if (dsn != null) {
            Matcher m = Pattern.compile("^(.*?)\\(([+-]?\\d+)\\)$").matcher(dsn);
            if (m.matches()) {
                dsn = m.group(1);
                rel = Integer.parseInt(m.group(2));
            }
            dsn = dsn.toUpperCase(Locale.ROOT);
        }
        Map<String, String> attrs = new LinkedHashMap<>(p);
        attrs.remove("DSN");
        attrs.remove("DSNAME");
        attrs.remove("DISP");
        String disp = p.get("DISP");
        return new Dd(name == null ? null : name.toUpperCase(Locale.ROOT), dsn, disp, rel, attrs, source, s.line());
    }

    /** Joins continuations and sets in-stream data aside, so what remains is one statement per entry. */
    List<Stmt> statements(List<String> raw) {
        List<Stmt> out = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            String l = cut(raw.get(i));
            if (!l.startsWith("//") || l.startsWith("//*")) continue;
            if (l.strip().equals("//")) break; // Null statement ends the job stream.
            int line = i + 1;
            String body = l.substring(2);
            String name = null;
            if (!body.isEmpty() && body.charAt(0) != ' ') {
                int sp = body.indexOf(' ');
                name = sp < 0 ? body : body.substring(0, sp);
                body = sp < 0 ? "" : body.substring(sp);
            }
            body = body.strip();
            int sp = firstBlank(body);
            String op = (sp < 0 ? body : body.substring(0, sp)).toUpperCase(Locale.ROOT);
            String rest = sp < 0 ? "" : body.substring(sp).strip();
            // IF conditions contain blanks by design; everywhere else the first blank starts a comment.
            if (!op.equals("IF")) rest = dropComment(rest);
            while (endsContinued(rest) && i + 1 < raw.size()) {
                String nx = cut(raw.get(i + 1));
                if (nx.startsWith("//*")) {
                    i++;
                    continue;
                }
                if (!nx.startsWith("//")) break;
                i++;
                String cont = dropComment(nx.substring(2).strip());
                rest = rest + cont;
            }
            List<String> inline = new ArrayList<>();
            if (op.equals("DD") && inlineStart(rest)) {
                String dlm = delimiter(rest);
                while (i + 1 < raw.size()) {
                    String nx = raw.get(i + 1);
                    if (dlm != null ? nx.startsWith(dlm) : nx.startsWith("/*") || nx.startsWith("//")) {
                        if (nx.startsWith("/*") || dlm != null) i++;
                        break;
                    }
                    i++;
                    inline.add(nx.length() > 72 ? nx.substring(0, 72) : nx);
                }
            }
            out.add(new Stmt(name, op, rest, line, inline));
        }
        return out;
    }

    private static String cut(String l) {
        return l.length() > 71 ? l.substring(0, 71) : l;
    }

    private static boolean inlineStart(String rest) {
        String up = rest.toUpperCase(Locale.ROOT);
        return up.equals("*") || up.startsWith("*,") || up.equals("DATA") || up.startsWith("DATA,");
    }

    private static String delimiter(String rest) {
        Matcher m = Pattern.compile("(?i)DLM=([^,\\s]+)").matcher(rest);
        return m.find() ? unquote(m.group(1)) : null;
    }

    private static int firstBlank(String s) {
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == ' ') return i;
        return -1;
    }

    /** Parameters end at the first blank outside quotes; anything after it is a comment. */
    private static String dropComment(String s) {
        boolean q = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'') q = !q;
            if (c == ' ' && !q) return s.substring(0, i);
        }
        return s;
    }

    private static boolean endsContinued(String s) {
        return s.endsWith(",");
    }

    public static String substitute(String s, Map<String, String> sym) {
        if (s == null || s.indexOf('&') < 0) return s;
        StringBuilder out = new StringBuilder();
        Matcher m = SYMBOL.matcher(s);
        int last = 0;
        while (m.find()) {
            // && is a temporary dataset, not a symbol.
            if (m.start() > 0 && s.charAt(m.start() - 1) == '&') continue;
            String v = sym.get(m.group(1).toUpperCase(Locale.ROOT));
            if (v == null) continue;
            out.append(s, last, m.start()).append(v);
            last = m.end();
        }
        out.append(s.substring(last));
        return out.toString();
    }

    /** KEY=VALUE pairs, upper-cased keys, respecting quotes and nested parentheses. */
    public static Map<String, String> params(String s) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String part : splitTop(s)) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).matches("[A-Za-z@#$][\\w@#$.]*")) {
                out.put(part.substring(0, eq).toUpperCase(Locale.ROOT), part.substring(eq + 1));
            }
        }
        return out;
    }

    private static String firstPositional(String s) {
        List<String> parts = splitTop(s);
        if (parts.isEmpty()) return null;
        String f = parts.get(0);
        return f.contains("=") || f.isEmpty() ? null : f;
    }

    private static List<String> splitTop(String s) {
        List<String> out = new ArrayList<>();
        int depth = 0, last = 0;
        boolean q = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'') q = !q;
            if (q) continue;
            if (c == '(') depth++;
            if (c == ')') depth--;
            if (c == ',' && depth == 0) {
                out.add(s.substring(last, i));
                last = i + 1;
            }
        }
        if (last < s.length()) out.add(s.substring(last));
        return out;
    }

    private static String unquote(String v) {
        if (v == null) return null;
        String t = v.strip();
        return t.length() >= 2 && t.startsWith("'") && t.endsWith("'") ? t.substring(1, t.length() - 1) : t;
    }

    /** IMS batch names its application program in the PARM. */
    public static String imsProgram(Step st) {
        if (st.pgm() == null || !IMS_PGM.matcher(st.pgm()).matches()) return null;
        String parm = st.params().get("PARM");
        if (parm == null) return null;
        String[] p = parm.replace("(", "").replace(")", "").replace("'", "").split(",");
        return p.length >= 2 && !p[1].isBlank() ? p[1].strip().toUpperCase(Locale.ROOT) : null;
    }
}
