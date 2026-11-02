package com.modtoolkit.parsers.cobol;

import com.modtoolkit.parsers.cobol.CobolFacts.*;
import com.modtoolkit.parsers.copybook.CopybookLayout;
import io.proleap.cobol.Cobol85BaseListener;
import io.proleap.cobol.Cobol85Parser.*;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;

/** Walks ProLeap's parse tree into {@link CobolFacts}, mapping every token line back to its member and line. */
final class FactExtractor extends Cobol85BaseListener {
    private static final Set<String> MQ_VERBS = Set.of("MQCONN", "MQCONNX", "MQDISC", "MQOPEN", "MQCLOSE", "MQPUT", "MQPUT1", "MQGET", "MQINQ", "MQSET", "MQBEGIN", "MQCMIT", "MQBACK");
    private static final Set<String> DLI_VERBS = Set.of("CBLTDLI", "AIBTDLI", "AERTDLI", "PLITDLI");

    private final ExpandedSource src;
    private final List<ExecBlock> execs;
    private final int[] toExpanded;
    private final CobolFacts facts = new CobolFacts();
    private final Deque<State> stack = new ArrayDeque<>();
    private State st;

    /** Per-program working state; the finished parts land in {@link ProgramFacts}. */
    private static final class State {
        final ProgramFacts p = new ProgramFacts();
        String section;
        String dataSection;
        String paragraph;
        int paraStart = -1;
        int paraComplexity = 1;
        int paraMaxNest;
        int nesting;
        boolean lastTerminal;
        boolean inProcedure;
        final List<CopybookLayout.Item> items = new ArrayList<>();
        final List<Object[]> itemExtra = new ArrayList<>();
        final List<PendingCond> conds = new ArrayList<>();
        final Map<String, Set<String>> literalsOf = new HashMap<>();
        final List<PendingCall> pendingCalls = new ArrayList<>();
        final List<String> paraOrder = new ArrayList<>();
        final Map<String, Integer> paraIdx = new HashMap<>();
    }

    private record PendingCond(int item, String name, List<String> values, Loc loc) {}
    private record PendingCall(String var, List<String> using, Loc loc, String paragraph) {}

    FactExtractor(ExpandedSource src, List<ExecBlock> execs, int[] toExpanded) {
        this.src = src;
        this.execs = execs;
        this.toExpanded = toExpanded;
    }

    CobolFacts extract(StartRuleContext tree) {
        ProLeapCobolParser.walk(this, tree);
        return facts;
    }

    private int expanded(int preLine) {
        return preLine - 1 < toExpanded.length && preLine >= 1 ? toExpanded[preLine - 1] : preLine;
    }

    private Loc loc(Token t) {
        return src.origin(expanded(t.getLine()));
    }

    private Loc loc(ParserRuleContext c) {
        return loc(c.getStart());
    }

    private static String up(String s) {
        return s == null ? null : s.toUpperCase(Locale.ROOT);
    }

    /** Original spacing between tokens is not preserved by getText; this is good enough for names and PICs. */
    private static String text(ParseTree t) {
        return t == null ? null : t.getText();
    }

    private static String unquote(String s) {
        if (s != null && s.length() >= 2 && (s.startsWith("'") && s.endsWith("'") || s.startsWith("\"") && s.endsWith("\""))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    @Override
    public void enterProgramUnit(ProgramUnitContext c) {
        if (st != null) stack.push(st);
        State s = new State();
        s.p.parent = st == null ? null : st.p.programId;
        s.p.start = loc(c);
        st = s;
    }

    @Override
    public void exitProgramUnit(ProgramUnitContext c) {
        closeParagraph(c.getStop());
        finish(st, c);
        facts.programs.add(st.p);
        st = stack.isEmpty() ? null : stack.pop();
    }

    @Override
    public void enterProgramIdParagraph(ProgramIdParagraphContext c) {
        st.p.programId = up(unquote(text(c.programName())));
    }

    @Override public void enterWorkingStorageSection(WorkingStorageSectionContext c) { st.dataSection = "WORKING-STORAGE"; }
    @Override public void enterLinkageSection(LinkageSectionContext c) { st.dataSection = "LINKAGE"; }
    @Override public void enterLocalStorageSection(LocalStorageSectionContext c) { st.dataSection = "LOCAL-STORAGE"; }
    @Override public void enterFileSection(FileSectionContext c) { st.dataSection = "FILE"; }

    @Override
    public void enterDataDescriptionEntryFormat1(DataDescriptionEntryFormat1Context c) {
        int level = Integer.parseInt(c.getStart().getText());
        String name = c.dataName() != null ? up(text(c.dataName())) : null;
        String pic = null, usage = null, odo = null, redefines = null;
        boolean signSep = false;
        Integer occMin = null, occMax = null;
        List<String> values = new ArrayList<>();
        for (DataPictureClauseContext x : c.dataPictureClause()) pic = up(text(x.pictureString()));
        for (DataUsageClauseContext x : c.dataUsageClause()) {
            usage = up(x.getText()).replaceFirst("^USAGE(IS)?", "");
        }
        for (DataSignClauseContext x : c.dataSignClause()) signSep = x.SEPARATE() != null;
        for (DataRedefinesClauseContext x : c.dataRedefinesClause()) redefines = up(text(x.dataName()));
        for (DataOccursClauseContext x : c.dataOccursClause()) {
            int a = Integer.parseInt(x.integerLiteral().getText());
            occMin = a;
            occMax = a;
            if (x.dataOccursTo() != null) occMax = Integer.parseInt(x.dataOccursTo().integerLiteral().getText());
            if (x.qualifiedDataName() != null) odo = up(text(x.qualifiedDataName()));
        }
        for (DataValueClauseContext x : c.dataValueClause()) {
            for (DataValueIntervalContext i : x.dataValueInterval()) values.add(unquote(text(i.dataValueIntervalFrom())));
        }
        int idx = st.items.size();
        st.items.add(new CopybookLayout.Item(level, name, pic, usage, signSep, occMax == null ? 1 : occMax, redefines));
        st.itemExtra.add(new Object[] {occMin, occMax, odo, loc(c), st.dataSection, values});
        if (name != null) {
            for (String v : values) literal(name, v);
        }
    }

    @Override
    public void enterDataDescriptionEntryFormat3(DataDescriptionEntryFormat3Context c) {
        List<String> vals = new ArrayList<>();
        for (DataValueIntervalContext i : c.dataValueClause().dataValueInterval()) {
            String v = unquote(text(i.dataValueIntervalFrom()));
            if (i.dataValueIntervalTo() != null) v += " THRU " + unquote(text(i.dataValueIntervalTo().literal()));
            vals.add(v);
        }
        st.conds.add(new PendingCond(st.items.size() - 1, up(text(c.conditionName())), vals, loc(c)));
    }

    @Override
    public void enterFileControlEntry(FileControlEntryContext c) {
        String name = c.selectClause() != null ? up(text(c.selectClause().fileName())) : null;
        String assign = null;
        for (FileControlClauseContext cl : c.fileControlClause()) {
            if (cl.assignClause() != null) {
                AssignClauseContext a = cl.assignClause();
                assign = a.assignmentName() != null ? up(text(a.assignmentName())) : a.literal() != null ? up(unquote(text(a.literal()))) : up(a.getText().replaceFirst("(?i)^ASSIGN(TO)?", ""));
            }
        }
        if (name != null) st.p.files.add(new FileFact(name, assign, loc(c)));
    }

    @Override
    public void enterProcedureDivision(ProcedureDivisionContext c) {
        st.inProcedure = true;
        if (c.procedureDivisionUsingClause() != null) {
            st.p.usingParams = true;
            String t = c.procedureDivisionUsingClause().getText().replaceFirst("(?i)^(USING|CHAINING)", "");
            st.p.usingList.addAll(identifiersOf(c.procedureDivisionUsingClause()));
            if (st.p.usingList.isEmpty() && !t.isEmpty()) st.p.usingList.add(up(t));
        }
        openParagraph(null, c.getStart()); // The unnamed start of the procedure division.
    }

    private List<String> identifiersOf(ParserRuleContext c) {
        List<String> out = new ArrayList<>();
        collectDataNames(c, out);
        return out;
    }

    private void collectDataNames(ParseTree t, List<String> out) {
        if (t instanceof DataNameContext d) {
            out.add(up(d.getText()));
            return;
        }
        for (int i = 0; i < t.getChildCount(); i++) collectDataNames(t.getChild(i), out);
    }

    @Override
    public void enterProcedureSectionHeader(ProcedureSectionHeaderContext c) {
        closeParagraph(c.getStart());
        st.section = up(text(c.sectionName()));
        openParagraph(st.section, c.getStart());
    }

    @Override
    public void enterParagraph(ParagraphContext c) {
        closeParagraph(c.getStart());
        openParagraph(up(text(c.paragraphName())), c.getStart());
    }

    private void openParagraph(String name, Token t) {
        st.paragraph = name;
        st.paraStart = expanded(t.getLine());
        st.paraComplexity = 1;
        st.paraMaxNest = 0;
        st.lastTerminal = false;
    }

    private void closeParagraph(Token next) {
        if (st.paraStart < 0) return;
        if (st.paragraph != null) {
            Loc start = src.origin(st.paraStart);
            int end = next == null ? st.paraStart : Math.max(st.paraStart, expanded(next.getLine()) - 1);
            // The range stays in the paragraph's own member even when it ends inside a copybook.
            while (end > st.paraStart && !src.origin(end).member().equals(start.member())) end--;
            st.paraIdx.put(st.paragraph, st.p.paragraphs.size());
            st.paraOrder.add(st.paragraph);
            st.p.paragraphs.add(new Para(st.section, st.paragraph, start, src.origin(end).line(), st.paraComplexity, st.paraMaxNest, st.lastTerminal));
        }
        st.p.maxNesting = Math.max(st.p.maxNesting, st.paraMaxNest);
        st.paraStart = -1;
    }

    private void branch() {
        st.paraComplexity++;
        st.p.cyclomatic++;
    }

    private void nest() {
        st.nesting++;
        st.paraMaxNest = Math.max(st.paraMaxNest, st.nesting);
    }

    @Override public void enterIfStatement(IfStatementContext c) { branch(); nest(); }
    @Override public void exitIfStatement(IfStatementContext c) { st.nesting--; }
    @Override public void enterEvaluateStatement(EvaluateStatementContext c) { nest(); }
    @Override public void exitEvaluateStatement(EvaluateStatementContext c) { st.nesting--; }
    @Override public void enterEvaluateWhen(EvaluateWhenContext c) { branch(); }
    @Override public void enterSearchWhen(SearchWhenContext c) { branch(); }
    @Override public void enterPerformUntil(PerformUntilContext c) { branch(); }
    @Override public void enterPerformVarying(PerformVaryingContext c) { branch(); }
    @Override public void enterAtEndPhrase(AtEndPhraseContext c) { branch(); }
    @Override public void enterInvalidKeyPhrase(InvalidKeyPhraseContext c) { branch(); }
    @Override public void enterOnSizeErrorPhrase(OnSizeErrorPhraseContext c) { branch(); }
    @Override public void enterOnExceptionClause(OnExceptionClauseContext c) { branch(); }
    @Override public void enterOnOverflowPhrase(OnOverflowPhraseContext c) { branch(); }
    @Override public void enterPerformInlineStatement(PerformInlineStatementContext c) { nest(); }
    @Override public void exitPerformInlineStatement(PerformInlineStatementContext c) { st.nesting--; }

    @Override
    public void enterStatement(StatementContext c) {
        // A paragraph ends terminally only if its last top-level statement is an unconditional transfer.
        if (st != null && st.nesting == 0) st.lastTerminal = false;
    }

    private void terminal() {
        if (st.nesting == 0) st.lastTerminal = true;
    }

    @Override
    public void enterStopStatement(StopStatementContext c) {
        if (c.RUN() != null) {
            st.p.stops.add(new Stop("STOP RUN", loc(c), st.paragraph));
            terminal();
        }
    }

    @Override
    public void enterGobackStatement(GobackStatementContext c) {
        st.p.stops.add(new Stop("GOBACK", loc(c), st.paragraph));
        terminal();
    }

    @Override
    public void enterExitStatement(ExitStatementContext c) {
        if (c.PROGRAM() != null) {
            st.p.stops.add(new Stop("EXIT PROGRAM", loc(c), st.paragraph));
            terminal();
        }
    }

    @Override
    public void enterCallStatement(CallStatementContext c) {
        List<String> using = new ArrayList<>();
        if (c.callUsingPhrase() != null) {
            for (CallUsingParameterContext u : c.callUsingPhrase().callUsingParameter()) using.addAll(callArgs(u));
        }
        Loc l = loc(c);
        if (c.literal() != null) {
            addStaticCall(up(unquote(text(c.literal()))), using, l);
        } else {
            st.pendingCalls.add(new PendingCall(baseName(c.identifier()), using, l, st.paragraph));
        }
        if (c.onExceptionClause() == null && c.onOverflowPhrase() == null) return;
    }

    private List<String> callArgs(ParseTree t) {
        List<String> out = new ArrayList<>();
        if (t instanceof CallByReferenceContext r) {
            out.add(r.identifier() != null ? baseName(r.identifier()) : up(r.getText()));
        } else if (t instanceof CallByContentContext r) {
            out.add(r.identifier() != null ? baseName(r.identifier()) : up(r.getText()));
        } else if (t instanceof CallByValueContext r) {
            out.add(r.identifier() != null ? baseName(r.identifier()) : up(r.getText()));
        } else {
            for (int i = 0; i < t.getChildCount(); i++) out.addAll(callArgs(t.getChild(i)));
        }
        return out;
    }

    private void addStaticCall(String target, List<String> using, Loc l) {
        recordCall(target, CallKind.STATIC, null, using, l, st.paragraph, st);
    }

    private void recordCall(String target, CallKind kind, String variable, List<String> using, Loc l, String para, State s) {
        s.p.calls.add(new Call(target, kind, variable, using, l, para));
        if (MQ_VERBS.contains(target)) {
            facts.features.add("MQ");
            s.p.mq.add(new Mq(target, null, l, para));
        } else if (DLI_VERBS.contains(target)) {
            facts.features.add("IMS");
            s.p.dli.add(new Dli(target, using.isEmpty() ? null : using.get(0), l, para));
        }
    }

    @Override
    public void enterCancelCall(CancelCallContext c) {
        String t = c.literal() != null ? up(unquote(text(c.literal()))) : c.identifier() != null ? baseName(c.identifier()) : up(c.getText());
        st.p.cancels.add(new Cancel(t, loc(c), st.paragraph));
        facts.features.add("CANCEL");
    }

    /** Drops qualification and subscripts: a data flow edge is about the item, not its position. */
    private String baseName(ParserRuleContext id) {
        List<String> names = new ArrayList<>();
        collectDataNames(id, names);
        if (!names.isEmpty()) return names.get(0);
        return up(id.getText().replaceAll("\\(.*", "").replaceAll("(?i)(OF|IN)[A-Z].*$", ""));
    }

    @Override
    public void enterPerformProcedureStatement(PerformProcedureStatementContext c) {
        List<ProcedureNameContext> names = c.procedureName();
        String target = up(procName(names.get(0)));
        String thru = names.size() > 1 ? up(procName(names.get(1))) : null;
        st.p.performs.add(new Perform(st.paragraph, target, thru, loc(c)));
    }

    private static String procName(ProcedureNameContext p) {
        return p.paragraphName() != null ? p.paragraphName().getText() : p.sectionName().getText();
    }

    @Override
    public void enterGoToStatementSimple(GoToStatementSimpleContext c) {
        st.p.gotos.add(new GoTo(st.paragraph, up(procName(c.procedureName())), false, loc(c)));
        terminal();
    }

    @Override
    public void enterGoToDependingOnStatement(GoToDependingOnStatementContext c) {
        for (ProcedureNameContext p : c.procedureName()) st.p.gotos.add(new GoTo(st.paragraph, up(procName(p)), true, loc(c)));
        facts.features.add("GO TO DEPENDING");
    }

    @Override public void enterAlterStatement(AlterStatementContext c) { facts.features.add("ALTER"); }

    @Override
    public void enterMoveToStatement(MoveToStatementContext c) {
        MoveToSendingAreaContext s = c.moveToSendingArea();
        boolean lit = s.literal() != null;
        String from = lit ? unquote(text(s.literal())) : baseName(s.identifier());
        for (IdentifierContext to : c.identifier()) {
            String t = baseName(to);
            st.p.moves.add(new Move("MOVE", from, t, lit, loc(c), st.paragraph));
            if (lit && s.literal().NONNUMERICLITERAL() != null) literal(t, from);
        }
    }

    @Override
    public void enterMoveCorrespondingToStatement(MoveCorrespondingToStatementContext c) {
        String from = baseName(c.moveCorrespondingToSendingArea().identifier());
        for (IdentifierContext to : c.identifier()) st.p.moves.add(new Move("MOVE CORRESPONDING", from, baseName(to), false, loc(c), st.paragraph));
    }

    @Override
    public void enterComputeStatement(ComputeStatementContext c) {
        List<String> from = new LinkedHashSetList(identifiersOf(c.arithmeticExpression())).list();
        for (ComputeStoreContext s : c.computeStore()) {
            String to = baseName(s.identifier());
            for (String f : from) st.p.moves.add(new Move("COMPUTE", f, to, false, loc(c), st.paragraph));
        }
    }

    @Override
    public void enterAddToStatement(AddToStatementContext c) {
        for (AddFromContext f : c.addFrom()) {
            if (f.identifier() == null) continue;
            for (AddToContext t : c.addTo()) st.p.moves.add(new Move("ADD", baseName(f.identifier()), baseName(t.identifier()), false, loc(c), st.paragraph));
        }
    }

    @Override
    public void enterSubtractFromStatement(SubtractFromStatementContext c) {
        for (SubtractSubtrahendContext f : c.subtractSubtrahend()) {
            if (f.identifier() == null) continue;
            for (SubtractMinuendContext t : c.subtractMinuend()) st.p.moves.add(new Move("SUBTRACT", baseName(f.identifier()), baseName(t.identifier()), false, loc(c), st.paragraph));
        }
    }

    private void literal(String var, String value) {
        if (value == null || value.isBlank()) return;
        st.literalsOf.computeIfAbsent(var, k -> new LinkedHashSet<>()).add(value.strip().toUpperCase(Locale.ROOT));
    }

    private void fileIo(String verb, ParseTree file, ParserRuleContext c) {
        if (file != null) st.p.fileIo.add(new FileIo(verb, up(file.getText()), loc(c), st.paragraph));
    }

    @Override public void enterReadStatement(ReadStatementContext c) { fileIo("READ", c.fileName(), c); }
    @Override public void enterWriteStatement(WriteStatementContext c) { fileIo("WRITE", c.recordName(), c); }
    @Override public void enterRewriteStatement(RewriteStatementContext c) { fileIo("REWRITE", c.recordName(), c); }
    @Override public void enterDeleteStatement(DeleteStatementContext c) { fileIo("DELETE", c.fileName(), c); }
    @Override public void enterStartStatement(StartStatementContext c) { fileIo("START", c.fileName(), c); }
    @Override public void enterOpenInput(OpenInputContext c) { fileIo("OPEN INPUT", c.fileName(), c); }
    @Override public void enterOpenOutput(OpenOutputContext c) { fileIo("OPEN OUTPUT", c.fileName(), c); }
    @Override public void enterOpenIOStatement(OpenIOStatementContext c) { for (var f : c.fileName()) fileIo("OPEN I-O", f, c); }
    @Override public void enterOpenExtendStatement(OpenExtendStatementContext c) { for (var f : c.fileName()) fileIo("OPEN EXTEND", f, c); }

    @Override
    public void enterSortStatement(SortStatementContext c) {
        facts.features.add("SORT");
        List<String> using = new ArrayList<>(), giving = new ArrayList<>();
        for (SortUsingContext u : c.sortUsing()) u.fileName().forEach(f -> using.add(up(f.getText())));
        for (SortGivingPhraseContext g : c.sortGivingPhrase()) g.sortGiving().forEach(f -> giving.add(up(f.fileName().getText())));
        st.p.sorts.add(new SortFact("SORT", up(text(c.fileName())), using, giving, loc(c), st.paragraph));
    }

    @Override
    public void enterMergeStatement(MergeStatementContext c) {
        facts.features.add("MERGE");
        List<String> using = new ArrayList<>(), giving = new ArrayList<>();
        for (MergeUsingContext u : c.mergeUsing()) u.fileName().forEach(f -> using.add(up(f.getText())));
        for (MergeGivingPhraseContext g : c.mergeGivingPhrase()) g.mergeGiving().forEach(f -> giving.add(up(f.fileName().getText())));
        st.p.sorts.add(new SortFact("MERGE", up(text(c.fileName())), using, giving, loc(c), st.paragraph));
    }

    private void finish(State s, ProgramUnitContext c) {
        ProgramFacts p = s.p;
        int startExp = expanded(c.getStart().getLine());
        int endExp = expanded(c.getStop().getLine());
        p.lineEnd = src.origin(endExp).line();
        layout(s);
        for (PendingCond pc : s.conds) {
            p.conditions.add(new Condition(pc.item(), pc.name(), pc.values(), pc.loc()));
            for (String v : pc.values()) if (pc.item() >= 0 && s.items.get(pc.item()).name() != null) { /* 88 values name domain, not call targets. */ }
        }
        attachExecs(s, startExp, endExp);
        for (PendingCall pc : s.pendingCalls) {
            Set<String> cands = s.literalsOf.getOrDefault(pc.var(), Set.of());
            if (cands.isEmpty()) {
                p.calls.add(new Call(pc.var(), CallKind.DYNAMIC_UNRESOLVED, pc.var(), pc.using(), pc.loc(), pc.paragraph()));
                p.dynamicCandidates.put(pc.var(), tableCandidates(p, pc.var()));
            } else {
                for (String t : cands) recordCall(t, CallKind.DYNAMIC_RESOLVED, pc.var(), pc.using(), pc.loc(), pc.paragraph(), s);
            }
        }
        attachMqQueues(s);
        p.kind = !p.cics.isEmpty() ? "cics" : p.usingParams ? "subroutine" : "batch";
        p.loc = countLoc(startExp, endExp);
        reachability(s);
        if (!p.sql.isEmpty()) facts.features.add("EXEC SQL");
        if (!p.cics.isEmpty()) facts.features.add("EXEC CICS");
        if (!p.dli.isEmpty()) facts.features.add("IMS");
        for (var di : p.dataItems) {
            if (di.odoRef() != null) facts.features.add("OCCURS DEPENDING ON");
            if (di.redefines() != null) facts.features.add("REDEFINES");
            if (di.usage() != null && di.usage().matches("COMP(UTATIONAL)?-3|PACKED-DECIMAL")) facts.features.add("COMP-3");
        }
        if (p.parent != null) facts.features.add("NESTED PROGRAM");
    }

    private int countLoc(int start, int end) {
        int n = 0;
        for (int i = start; i <= end && i <= src.lines().size(); i++) {
            String l = src.lines().get(i - 1);
            boolean comment = !src.free() && l.length() > 6 && "*/$".indexOf(l.charAt(6)) >= 0 || l.stripLeading().startsWith("*>");
            if (!l.isBlank() && !comment) n++;
        }
        return n;
    }

    private void layout(State s) {
        // The level-stack parent of 88s is the item before them; 88s are not in items, so indexes line up.
        List<CopybookLayout.FieldLayout> lay = CopybookLayout.compute(s.items);
        for (int i = 0; i < lay.size(); i++) {
            var f = lay.get(i);
            var it = f.item();
            Object[] x = s.itemExtra.get(i);
            @SuppressWarnings("unchecked") List<String> values = (List<String>) x[5];
            s.p.dataItems.add(new DataItem(i, f.parent(), it.level(), it.name(), it.pic(), it.usage(), it.signSeparate(), (Integer) x[0], (Integer) x[1], (String) x[2], it.redefines(), (Loc) x[3], (String) x[4], values, f.offset(), f.length(), f.totalLength()));
        }
    }

    /** Assigns pre-scanned EXEC blocks inside this program to its paragraphs by line. */
    private void attachExecs(State s, int startExp, int endExp) {
        for (ExecBlock b : execs) {
            if (b.startLine() < startExp || b.startLine() > endExp) continue;
            // A nested program claims its own blocks: they fall inside the enclosing range too.
            if (facts.programs.stream().anyMatch(q -> s.p.programId != null && s.p.programId.equals(q.parent) && contains(q, b.startLine()))) continue;
            Loc l = src.origin(b.startLine());
            String para = paragraphAt(s, l);
            switch (b.kind()) {
                case "SQL" -> addSql(s, b, l, para);
                case "CICS" -> addCics(s, b, l, para);
                case "DLI" -> { facts.features.add("IMS"); s.p.dli.add(new Dli("EXEC DLI", b.text(), l, para)); }
                default -> facts.features.add("EXEC " + b.kind());
            }
        }
    }

    private boolean contains(ProgramFacts q, int expandedLine) {
        Loc o = src.origin(expandedLine);
        return q.start != null && o.member().equals(q.start.member()) && o.line() >= q.start.line() && o.line() <= q.lineEnd;
    }

    private String paragraphAt(State s, Loc l) {
        String found = null;
        for (Para p : s.p.paragraphs) {
            if (p.start().member().equals(l.member()) && p.start().line() <= l.line() && l.line() <= p.lineEnd()) found = p.name();
        }
        return found;
    }

    private void addSql(State s, ExecBlock b, Loc l, String para) {
        var q = ExecParsers.sql(b.text());
        s.p.sql.add(new Sql(q.type(), q.tables(), q.columns(), q.hostVars(), b.text(), l, src.origin(b.endLine()).line(), para));
    }

    private void addCics(State s, ExecBlock b, Loc l, String para) {
        var cmd = ExecParsers.cics(b.text());
        String[] res = ExecParsers.cicsResource(cmd);
        String kind = null, name = null;
        boolean resolved = false;
        List<String> names = new ArrayList<>();
        if (res != null) {
            kind = res[0];
            String lit = ExecParsers.literalOf(res[1]);
            if (lit != null) {
                names.add(lit.toUpperCase(Locale.ROOT));
                resolved = true;
            } else {
                String var = res[1].replaceAll("\\(.*", "").strip().toUpperCase(Locale.ROOT);
                Set<String> c = s.literalsOf.getOrDefault(var, Set.of());
                if (c.isEmpty()) {
                    names.add(var);
                    s.p.dynamicCandidates.put(var, tableCandidates(s.p, var));
                } else {
                    names.addAll(c);
                    resolved = true;
                }
            }
        }
        Map<String, String> attrs = new java.util.LinkedHashMap<>(cmd.options());
        if (names.isEmpty()) {
            s.p.cics.add(new Cics(cmd.command(), null, null, false, attrs, l, para));
        }
        for (String n : names) s.p.cics.add(new Cics(cmd.command(), kind, n, resolved, attrs, l, para));
    }

    /**
     * The common dispatch table: FILLER items with VALUEs under a group, and a REDEFINES of it whose OCCURS element
     * holds the program name. The target is whichever one the subscript picks, so all of them are candidates.
     */
    private static List<String> tableCandidates(ProgramFacts p, String var) {
        DataItem item = p.dataItems.stream().filter(d -> var.equals(d.name())).findFirst().orElse(null);
        Set<String> out = new LinkedHashSet<>();
        while (item != null) {
            if (item.redefines() != null) {
                String target = item.redefines();
                DataItem base = p.dataItems.stream().filter(d -> target.equals(d.name())).findFirst().orElse(null);
                if (base != null) {
                    for (DataItem d : p.dataItems) {
                        if (isUnder(p, d, base)) d.values().stream().map(v -> v.strip().toUpperCase(Locale.ROOT)).filter(v -> v.matches("[A-Z@#$][A-Z0-9@#$]{0,7}")).forEach(out::add);
                    }
                }
                break;
            }
            item = item.parent() < 0 ? null : p.dataItems.get(item.parent());
        }
        return new ArrayList<>(out);
    }

    private static boolean isUnder(ProgramFacts p, DataItem d, DataItem base) {
        DataItem cur = d;
        while (cur != null) {
            if (cur.index() == base.index()) return true;
            cur = cur.parent() < 0 ? null : p.dataItems.get(cur.parent());
        }
        return false;
    }

    /** A queue name reaches MQOPEN through MOVE ... TO MQOD-OBJECTNAME; take every literal moved there. */
    private void attachMqQueues(State s) {
        Set<String> queues = new LinkedHashSet<>();
        for (Map.Entry<String, Set<String>> e : s.literalsOf.entrySet()) {
            if (e.getKey().endsWith("OBJECTNAME")) queues.addAll(e.getValue());
        }
        // Follow one hop: MOVE WS-QUEUE TO MQOD-OBJECTNAME, where WS-QUEUE has a VALUE.
        for (Move m : s.p.moves) {
            if (!m.literal() && m.to().endsWith("OBJECTNAME")) queues.addAll(s.literalsOf.getOrDefault(m.from(), Set.of()));
        }
        if (queues.isEmpty()) return;
        List<Mq> withQueues = new ArrayList<>();
        for (Mq q : s.p.mq) {
            if (q.verb().startsWith("MQPUT") || q.verb().equals("MQGET")) {
                for (String n : queues) withQueues.add(new Mq(q.verb(), n, q.loc(), q.paragraph()));
            } else {
                withQueues.add(q);
            }
        }
        s.p.mq.clear();
        s.p.mq.addAll(withQueues);
    }

    /** Conservative: a paragraph is flagged only when no PERFORM, GO TO or fall-through can reach it. */
    private void reachability(State s) {
        List<Para> paras = s.p.paragraphs;
        if (paras.isEmpty()) return;
        Set<String> names = new HashSet<>();
        paras.forEach(p -> names.add(p.name()));
        Map<String, Set<String>> edges = new HashMap<>();
        for (Perform pf : s.p.performs) {
            String from = pf.paragraph() == null ? "" : pf.paragraph();
            addRange(edges, from, pf.target(), pf.thru(), s);
        }
        for (GoTo g : s.p.gotos) edges.computeIfAbsent(g.paragraph() == null ? "" : g.paragraph(), k -> new HashSet<>()).add(g.target());
        Set<String> seen = new HashSet<>();
        Deque<String> work = new ArrayDeque<>();
        work.add("");
        // Control enters at the procedure division's first statement, then falls into the first paragraph.
        boolean bodyEnds = s.p.stops.stream().anyMatch(x -> x.paragraph() == null);
        boolean bodyGoes = s.p.gotos.stream().anyMatch(x -> x.paragraph() == null && !x.depending());
        if (!(bodyEnds || bodyGoes)) work.add(paras.get(0).name());
        while (!work.isEmpty()) {
            String n = work.poll();
            if (!seen.add(n)) continue;
            for (String t : edges.getOrDefault(n, Set.of())) work.add(t);
            Integer i = s.paraIdx.get(n);
            if (i != null && !paras.get(i).terminal() && i + 1 < paras.size()) work.add(paras.get(i + 1).name());
        }
        for (Para p : paras) if (!seen.contains(p.name())) s.p.unreachable.add(p.name());
    }

    private void addRange(Map<String, Set<String>> edges, String from, String target, String thru, State s) {
        Set<String> e = edges.computeIfAbsent(from, k -> new HashSet<>());
        e.add(target);
        if (thru == null) return;
        Integer a = s.paraIdx.get(target), b = s.paraIdx.get(thru);
        if (a != null && b != null) for (int i = a; i <= b; i++) e.add(s.paraOrder.get(i));
    }

    /** Small helper: order-preserving de-duplication. */
    private record LinkedHashSetList(List<String> in) {
        List<String> list() {
            return new ArrayList<>(new LinkedHashSet<>(in));
        }
    }
}
