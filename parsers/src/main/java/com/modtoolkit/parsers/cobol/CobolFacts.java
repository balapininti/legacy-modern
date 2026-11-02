package com.modtoolkit.parsers.cobol;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Everything extracted from one source member. Every fact carries provenance; nothing is stored without it. */
public final class CobolFacts {
    public final List<ProgramFacts> programs = new ArrayList<>();
    public final TreeSet<String> features = new TreeSet<>();

    public record Para(String section, String name, Loc start, int lineEnd, int complexity, int maxNesting, boolean terminal) {}
    public record DataItem(int index, int parent, int level, String name, String pic, String usage, boolean signSeparate,
            Integer occursMin, Integer occursMax, String odoRef, String redefines, Loc loc, String section, List<String> values,
            int offset, int length, int totalLength) {}
    public record Condition(int dataItem, String name, List<String> values, Loc loc) {}
    public record FileFact(String name, String assign, Loc loc) {}
    /** target is the program name; for an unresolved dynamic call it is the variable. */
    public enum CallKind { STATIC, DYNAMIC_RESOLVED, DYNAMIC_UNRESOLVED }
    public record Call(String target, CallKind kind, String variable, List<String> using, Loc loc, String paragraph) {}
    public record Perform(String paragraph, String target, String thru, Loc loc) {}
    public record GoTo(String paragraph, String target, boolean depending, Loc loc) {}
    public record Sql(String stmtType, List<String> tables, List<String> columns, List<String> hostVars, String text, Loc loc, int lineEnd, String paragraph) {}
    public record Cics(String command, String resourceKind, String resourceName, boolean resourceResolved, Map<String, String> attrs, Loc loc, String paragraph) {}
    public record Mq(String verb, String queue, Loc loc, String paragraph) {}
    public record Dli(String verb, String detail, Loc loc, String paragraph) {}
    public record SortFact(String verb, String file, List<String> using, List<String> giving, Loc loc, String paragraph) {}
    public record FileIo(String verb, String file, Loc loc, String paragraph) {}
    public record Move(String kind, String from, String to, boolean literal, Loc loc, String paragraph) {}
    public record Stop(String verb, Loc loc, String paragraph) {}
    public record Cancel(String target, Loc loc, String paragraph) {}

    public static final class ProgramFacts {
        public String programId;
        public String parent;
        public Loc start;
        public int lineEnd;
        public String kind = "batch";
        public boolean usingParams;
        public List<String> usingList = new ArrayList<>();
        public int loc;
        public int cyclomatic = 1;
        public int maxNesting;
        public final List<Para> paragraphs = new ArrayList<>();
        public final List<DataItem> dataItems = new ArrayList<>();
        public final List<Condition> conditions = new ArrayList<>();
        public final List<FileFact> files = new ArrayList<>();
        public final List<Call> calls = new ArrayList<>();
        public final List<Perform> performs = new ArrayList<>();
        public final List<GoTo> gotos = new ArrayList<>();
        public final List<Sql> sql = new ArrayList<>();
        public final List<Cics> cics = new ArrayList<>();
        public final List<Mq> mq = new ArrayList<>();
        public final List<Dli> dli = new ArrayList<>();
        public final List<SortFact> sorts = new ArrayList<>();
        public final List<FileIo> fileIo = new ArrayList<>();
        public final List<Move> moves = new ArrayList<>();
        public final List<Stop> stops = new ArrayList<>();
        public final List<Cancel> cancels = new ArrayList<>();
        public final java.util.Set<String> unreachable = new java.util.TreeSet<>();
    }
}
