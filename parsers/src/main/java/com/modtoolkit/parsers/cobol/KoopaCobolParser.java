package com.modtoolkit.parsers.cobol;

import java.io.File;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import koopa.cobol.parser.CobolParser;
import koopa.cobol.parser.ParseResults;
import koopa.cobol.projects.BasicCobolProject;
import koopa.cobol.sources.SourceFormat;
import koopa.core.trees.KoopaTreeBuilder;
import koopa.core.trees.Tree;

/**
 * The fallback for members ProLeap rejects, usually for a dialect extension. Koopa is an island parser: it recognizes
 * what it knows and passes over the rest, so a member parsed this way is marked FALLBACK PARSER in its features, since
 * facts from a skipped statement would be absent rather than wrong.
 */
public final class KoopaCobolParser implements com.modtoolkit.parsers.cobol.CobolParser {
    private static final Set<String> CONDITIONAL_BRANCHES = Set.of("ifStatement", "evaluateWhen", "searchWhen", "atEnd", "invalidKey", "onSizeError", "onException", "onOverflow", "until", "varying");
    private static final Pattern MOVE = Pattern.compile("(?is)^MOVE\\s+(CORRESPONDING\\s+|CORR\\s+)?(ALL\\s+)?(.+?)\\s+TO\\s+(.+)$");
    private static final Pattern COMPUTE = Pattern.compile("(?is)^COMPUTE\\s+(.+?)\\s*(?:=|EQUAL)\\s*(.+)$");
    private static final Pattern WORD = Pattern.compile("[A-Za-z0-9][A-Za-z0-9-]*");
    private static final Pattern USING = Pattern.compile("(?is)\\bUSING\\s+(.+)$");
    private static final Set<String> NOT_DATA = Set.of("BY", "REFERENCE", "CONTENT", "VALUE", "ADDRESS", "OF", "LENGTH", "OMITTED", "ROUNDED", "ON", "SIZE", "ERROR", "NOT", "RETURNING", "GIVING", "END-COMPUTE", "END-CALL", "EXCEPTION", "OVERFLOW", "FUNCTION");

    @Override
    public String name() {
        return "koopa";
    }

    @Override
    public CobolFacts parse(ExpandedSource src) {
        ParserInput.Result in = ParserInput.prepare(src);
        // Long joined lines are real code here, and variable format does not cut them at column 72.
        BasicCobolProject project = new BasicCobolProject();
        project.setDefaultFormat(SourceFormat.VARIABLE);
        CobolParser parser = new CobolParser();
        parser.setProject(project);
        parser.setBuildTrees(true);
        parser.setKeepingTrackOfTokens(false);
        ParseResults r;
        try {
            r = parser.parse(new File(src.member() + ".cbl"), new StringReader(String.join("\n", in.lines()) + "\n"));
        } catch (java.io.IOException | RuntimeException e) {
            throw new CobolParseException(e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()), null);
        }
        if (!r.isValidInput()) throw new CobolParseException("koopa rejected the member", null);
        List<Tree> trees = r.getParse().getTarget(KoopaTreeBuilder.class).getTrees();
        FactExtractor b = new FactExtractor(src, in.execs(), null);
        for (Tree t : trees) walk(t, b);
        CobolFacts f = b.result();
        if (f.programs.isEmpty()) throw new CobolParseException("koopa found no program", null);
        f.features.add("FALLBACK PARSER koopa");
        return f;
    }

    private static int line(Tree t) {
        return t.getStartPosition() == null ? 1 : t.getStartPosition().getLinenumber();
    }

    private static int endLine(Tree t) {
        return t.getEndPosition() == null ? line(t) : t.getEndPosition().getLinenumber();
    }

    private static String text(Tree t) {
        String s = t.getProgramText();
        return s == null ? "" : s.strip();
    }

    private void walk(Tree t, FactExtractor b) {
        String n = t.getName() == null ? "" : t.getName();
        switch (n) {
            case "programDefinition" -> {
                b.beginProgram(line(t));
                children(t, b);
                b.endProgram(line(t), endLine(t));
            }
            case "programIdParagraph" -> {
                Tree name = first(t, "programName");
                if (name != null) b.programName(text(name));
            }
            case "workingStorageSection" -> section(t, b, "WORKING-STORAGE");
            case "linkageSection" -> section(t, b, "LINKAGE");
            case "localStorageSection" -> section(t, b, "LOCAL-STORAGE");
            case "fileSection" -> section(t, b, "FILE");
            case "dataDescriptionEntry" -> data(t, b);
            case "selectStatement" -> select(t, b);
            case "procedureDivision" -> {
                Tree using = find(first(t, "header"), "using");
                List<String> names = new ArrayList<>();
                if (using != null) collect(using, "dataName", names);
                b.procedureUsing(names, line(t));
                children(t, b);
            }
            case "sectionName" -> b.section(text(t), line(t));
            case "paragraphName" -> b.paragraph(text(t), line(t));
            case "callStatement" -> {
                Tree target = first(t, "programName");
                b.statement();
                List<String> using = using(text(t));
                if (target != null) {
                    String tx = text(target);
                    if (tx.startsWith("'") || tx.startsWith("\"")) b.callLiteral(tx, using, line(t));
                    else b.callVariable(tx.replaceAll("\\(.*", ""), using, line(t));
                }
                children(t, b);
            }
            case "performStatement" -> {
                b.statement();
                List<Tree> procs = direct(t, "procedureName");
                if (!procs.isEmpty()) b.perform(text(procs.get(0)), procs.size() > 1 ? text(procs.get(1)) : null, line(t));
                else {
                    b.nestIn();
                    children(t, b);
                    b.nestOut();
                    return;
                }
                children(t, b);
            }
            case "goToStatement" -> {
                b.statement();
                List<Tree> procs = new ArrayList<>();
                all(t, "procedureName", procs);
                boolean dep = text(t).toUpperCase(Locale.ROOT).contains("DEPENDING");
                for (Tree p : procs) b.goTo(text(p), dep, line(t));
            }
            case "moveStatement" -> {
                b.statement();
                move(text(t), line(t), b);
            }
            case "computeStatement" -> {
                b.statement();
                compute(text(t), line(t), b);
                children(t, b);
            }
            case "stopStatement" -> {
                b.statement();
                if (text(t).toUpperCase(Locale.ROOT).contains("RUN")) b.stop("STOP RUN", line(t));
            }
            case "gobackStatement" -> {
                b.statement();
                b.stop("GOBACK", line(t));
            }
            case "exitStatement" -> {
                b.statement();
                if (text(t).toUpperCase(Locale.ROOT).contains("PROGRAM")) b.stop("EXIT PROGRAM", line(t));
            }
            case "cancelStatement" -> {
                b.statement();
                Matcher m = Pattern.compile("(?is)^CANCEL\\s+(\\S+)").matcher(text(t));
                if (m.find()) b.cancel(m.group(1), line(t));
            }
            case "readStatement" -> io("READ", t, b);
            case "writeStatement" -> io("WRITE", t, b);
            case "rewriteStatement" -> io("REWRITE", t, b);
            case "deleteStatement" -> io("DELETE", t, b);
            case "startStatement" -> io("START", t, b);
            case "ifStatement" -> {
                b.statement();
                b.branched();
                b.nestIn();
                children(t, b);
                b.nestOut();
            }
            case "evaluateStatement" -> {
                b.statement();
                b.nestIn();
                children(t, b);
                b.nestOut();
            }
            case "sortStatement", "mergeStatement" -> {
                b.statement();
                String[] w = text(t).split("\\s+");
                if (w.length > 1) b.sort(n.equals("sortStatement") ? "SORT" : "MERGE", w[1], List.of(), List.of(), line(t));
            }
            default -> {
                if (CONDITIONAL_BRANCHES.contains(n)) b.branched();
                children(t, b);
            }
        }
    }

    private void section(Tree t, FactExtractor b, String name) {
        b.dataSection(name);
        children(t, b);
    }

    private void children(Tree t, FactExtractor b) {
        for (Tree c : t.childTrees()) walk(c, b);
    }

    private void io(String verb, Tree t, FactExtractor b) {
        b.statement();
        String[] w = text(t).split("\\s+");
        if (w.length > 1) b.fileIoAt(verb, w[1], line(t));
        children(t, b);
    }

    private void data(Tree t, FactExtractor b) {
        Tree lvl = first(t, "levelNumber");
        if (lvl == null) return;
        int level;
        try {
            level = Integer.parseInt(text(lvl));
        } catch (NumberFormatException e) {
            return;
        }
        Tree entry = first(t, "entryName");
        String name = entry == null ? null : text(entry);
        if ("FILLER".equalsIgnoreCase(name)) name = null;
        List<String> values = new ArrayList<>();
        Tree val = first(t, "valueClause");
        if (val != null) for (Tree l : direct(val, "literal")) values.add(unquote(text(l)));
        if (level == 88) {
            if (name == null) return; // An island parse can lose the name; better no fact than a nameless one.
            List<String> v = values.size() == 2 && text(val).toUpperCase(Locale.ROOT).matches(".*\\bTHRU?(UGH)?\\b.*") ? List.of(values.get(0) + " THRU " + values.get(1)) : values;
            b.condition(name, v, line(t));
            return;
        }
        if (level == 66) return;
        Tree pic = find(t, "pictureString");
        Tree usage = find(t, "usageOperand");
        Tree sign = first(t, "signClause");
        Tree occurs = first(t, "occursClause");
        Integer min = null, max = null;
        String odo = null;
        if (occurs != null) {
            Tree mn = find(occurs, "min"), mx = find(occurs, "max");
            if (mn != null && mx != null) {
                min = Integer.valueOf(text(mn));
                max = Integer.valueOf(text(mx));
            } else {
                Matcher m = Pattern.compile("(?i)OCCURS\\s+(\\d+)").matcher(text(occurs));
                if (m.find()) min = max = Integer.valueOf(m.group(1));
            }
            Tree dep = find(occurs, "dataName");
            if (text(occurs).toUpperCase(Locale.ROOT).contains("DEPENDING") && dep != null) odo = text(dep);
        }
        Tree red = first(t, "redefinesClause");
        String redefines = red == null || find(red, "dataName") == null ? null : text(find(red, "dataName"));
        b.dataItem(level, name, pic == null ? null : text(pic), usage == null ? null : text(usage).replaceFirst("(?i)^USAGE\\s+(IS\\s+)?", ""), sign != null && text(sign).toUpperCase(Locale.ROOT).contains("SEPARATE"), min, max, odo, redefines, values, line(t));
    }

    private void select(Tree t, FactExtractor b) {
        Tree sel = first(t, "selectClause"), as = first(t, "assignClause");
        if (sel == null) return;
        String[] w = text(sel).split("\\s+");
        String file = w[w.length - 1];
        Tree name = as == null ? null : find(as, "name");
        String assign = name == null ? (as == null ? null : text(as).replaceFirst("(?i)^ASSIGN\\s+(TO\\s+)?", "")) : text(name);
        b.file(file, assign == null ? null : unquote(assign), line(t));
    }

    private void move(String stmt, int line, FactExtractor b) {
        Matcher m = MOVE.matcher(stmt.replaceAll("\\s+", " "));
        if (!m.find()) return;
        String from = m.group(3).strip();
        boolean lit = from.startsWith("'") || from.startsWith("\"") || from.matches("[+-]?[0-9.]+") || from.toUpperCase(Locale.ROOT).matches("SPACES?|ZEROE?S?|LOW-VALUES?|HIGH-VALUES?|QUOTES?|NULLS?");
        String source = lit ? from : base(from);
        String kind = m.group(1) == null ? "MOVE" : "MOVE CORRESPONDING";
        for (String to : targets(m.group(4))) b.move(kind, source, to, lit, line);
    }

    private void compute(String stmt, int line, FactExtractor b) {
        Matcher m = COMPUTE.matcher(stmt.replaceAll("\\s+", " "));
        if (!m.find()) return;
        List<String> sources = new ArrayList<>();
        Matcher w = WORD.matcher(m.group(2));
        while (w.find()) {
            String v = w.group();
            if (!v.matches("[0-9]+") && !NOT_DATA.contains(v.toUpperCase(Locale.ROOT)) && !sources.contains(v)) sources.add(v);
        }
        for (String to : targets(m.group(1))) for (String f : sources) b.move("COMPUTE", f, to, false, line);
    }

    /** Names at the top level of a receiving list: subscripts and qualifiers are not items of their own. */
    private static List<String> targets(String list) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (char c : (list + " ").toCharArray()) {
            if (c == '(') depth++;
            if (c == ')') depth--;
            if (depth == 0 && (c == ' ' || c == ',')) {
                String w = cur.toString().strip();
                cur.setLength(0);
                if (w.isEmpty()) continue;
                String u = w.toUpperCase(Locale.ROOT);
                if (u.equals("OF") || u.equals("IN")) {
                    if (!out.isEmpty()) out.remove(out.size() - 1); // Rebuilt below: the qualified name stays the first word.
                    continue;
                }
                if (!NOT_DATA.contains(u)) out.add(base(w));
            } else if (c != ',') {
                cur.append(c);
            }
        }
        return out;
    }

    private static String base(String s) {
        return s.replaceAll("\\(.*", "").strip().split("\\s+")[0];
    }

    private static List<String> using(String call) {
        Matcher m = USING.matcher(call.replaceAll("\\s+", " "));
        List<String> out = new ArrayList<>();
        if (!m.find()) return out;
        for (String w : m.group(1).replaceAll("\\([^)]*\\)", " ").split("[\\s,]+")) {
            String u = w.toUpperCase(Locale.ROOT);
            if (u.isEmpty() || NOT_DATA.contains(u)) {
                if (u.equals("RETURNING") || u.equals("ON") || u.equals("NOT") || u.equals("END-CALL")) break;
                continue;
            }
            out.add(w.startsWith("'") || w.startsWith("\"") ? w : w);
        }
        return out;
    }

    private static String unquote(String s) {
        return s != null && s.length() >= 2 && (s.startsWith("'") && s.endsWith("'") || s.startsWith("\"") && s.endsWith("\"")) ? s.substring(1, s.length() - 1) : s;
    }

    private static Tree first(Tree t, String name) {
        if (t == null) return null;
        for (Tree c : t.childTrees()) if (name.equals(c.getName())) return c;
        return null;
    }

    private static List<Tree> direct(Tree t, String name) {
        List<Tree> out = new ArrayList<>();
        for (Tree c : t.childTrees()) if (name.equals(c.getName())) out.add(c);
        return out;
    }

    private static Tree find(Tree t, String name) {
        if (t == null) return null;
        for (Tree c : t.childTrees()) {
            if (name.equals(c.getName())) return c;
            Tree x = find(c, name);
            if (x != null) return x;
        }
        return null;
    }

    private static void all(Tree t, String name, List<Tree> out) {
        for (Tree c : t.childTrees()) {
            if (name.equals(c.getName())) out.add(c);
            else all(c, name, out);
        }
    }

    private static void collect(Tree t, String name, List<String> out) {
        for (Tree c : t.childTrees()) {
            if (name.equals(c.getName())) out.add(text(c));
            else collect(c, name, out);
        }
    }
}
