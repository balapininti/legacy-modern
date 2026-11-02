package com.modtoolkit.discovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modtoolkit.discovery.NodeStore.Key;
import com.modtoolkit.parsers.cobol.CobolFacts;
import com.modtoolkit.parsers.cobol.CobolFacts.*;
import com.modtoolkit.parsers.cobol.CopybookLibrary;
import com.modtoolkit.parsers.cobol.ExpandedSource;
import com.modtoolkit.parsers.cobol.Loc;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/** Writes one member's facts. Runs inside the member's transaction; a retry replaces, never duplicates. */
final class FactWriter {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final NodeStore nodes;

    FactWriter(JdbcTemplate jdbc, NodeStore nodes) {
        this.jdbc = jdbc;
        this.nodes = nodes;
    }

    static void clearMember(JdbcTemplate jdbc, long memberId) {
        // Only edges this member's parse produced. An edge can cite another member as provenance (a statement in a
        // copybook a program expanded, a step in a PROC a job expanded); it belongs to whoever expanded it.
        jdbc.update("DELETE FROM graph_edge WHERE owner_member_id = ?", memberId);
        for (String t : List.of("missing_copybook", "dialect_feature", "copy_use", "jcl_job", "bms_map", "db2_table", "copybook")) {
            jdbc.update("DELETE FROM " + t + " WHERE member_id = ?", memberId);
        }
        jdbc.update("DELETE FROM data_item WHERE owner_member_id = ? AND program_id IS NULL", memberId);
        // Program rows themselves stay: migration status, reviews and knowledge hang off their ids. Only their facts go.
        String mine = "SELECT id FROM program WHERE member_id = " + memberId;
        for (String t : List.of("graph_edge", "paragraph", "data_item", "sql_stmt", "cics_cmd", "unresolved_call", "copy_use")) {
            jdbc.update("DELETE FROM " + t + " WHERE program_id IN (" + mine + ")");
        }
    }

    private static String json(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private record Edge(long src, long dst, String type, Long programId, long memberId, int line, Map<String, Object> attrs) {}

    /** Maps a provenance member name to its id, through the same resolution the expansion used. */
    private final class Members {
        final long self;
        final String selfName;
        final Map<String, Long> ids = new HashMap<>();
        final CopybookLibrary lib;
        final Map<String, Long> byPath;

        Members(long self, String selfName, CopybookLibrary lib, Map<String, Long> byPath) {
            this.self = self;
            this.selfName = selfName;
            this.lib = lib;
            this.byPath = byPath;
        }

        long id(Loc l) {
            if (l.member().equals(selfName)) return self;
            return ids.computeIfAbsent(l.member(), n -> lib.resolve(n).map(e -> byPath.getOrDefault(e.path(), self)).orElse(self));
        }
    }

    void writeProgramMember(long memberId, String memberName, ExpandedSource src, CobolFacts facts, CopybookLibrary lib, Map<String, Long> memberIdByPath) {
        Members mem = new Members(memberId, memberName, lib, memberIdByPath);
        Map<String, Long> progIds = new HashMap<>();
        // The walker finishes nested programs first; parents must exist before their children point at them.
        List<ProgramFacts> ordered = new ArrayList<>(facts.programs);
        ordered.sort((a, b) -> Integer.compare(depth(a, facts), depth(b, facts)));
        long topId = -1;
        for (ProgramFacts p : ordered) {
            long id = upsertProgram(memberId, p, p.parent == null ? null : progIds.get(p.parent));
            progIds.put(p.programId, id);
            if (topId < 0) topId = id;
        }
        // A program no longer in this member is gone, and only then does its history go with it.
        for (Map<String, Object> old : jdbc.queryForList("SELECT id, program_id FROM program WHERE member_id = ?", memberId)) {
            if (!progIds.containsKey(old.get("program_id"))) jdbc.update("DELETE FROM program WHERE id = ?", old.get("id"));
        }
        for (ExpandedSource.CopyUse c : src.copies()) {
            Long cbId = null;
            if (c.found() && c.path() != null) {
                Long mid = memberIdByPath.get(c.path());
                if (mid != null) {
                    List<Long> ids = jdbc.queryForList("SELECT id FROM copybook WHERE member_id = ?", Long.class, mid);
                    if (!ids.isEmpty()) cbId = ids.get(0);
                }
            }
            jdbc.update("INSERT INTO copy_use (program_id, copybook_id, copybook_name, replacing, line, member_id) VALUES (?,?,?,?::jsonb,?,?)",
                    topId, cbId, c.name().toUpperCase(Locale.ROOT), json(c.replacing()), c.at().line(), memberId);
        }
        for (String miss : new HashSet<>(src.missing())) {
            jdbc.update("INSERT INTO missing_copybook (member_id, name) VALUES (?,?)", memberId, miss);
        }
        for (String f : facts.features) {
            jdbc.update("INSERT INTO dialect_feature (member_id, feature) VALUES (?,?)", memberId, f);
        }
        for (ProgramFacts p : ordered) writeProgram(p, progIds.get(p.programId), memberId, memberName, src, mem);
    }

    private int depth(ProgramFacts p, CobolFacts all) {
        int d = 0;
        String cur = p.parent;
        while (cur != null) {
            d++;
            String c = cur;
            cur = all.programs.stream().filter(x -> c.equals(x.programId)).map(x -> x.parent).findFirst().orElse(null);
        }
        return d;
    }

    private long upsertProgram(long memberId, ProgramFacts p, Long parent) {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("loc", p.loc);
        metrics.put("cyclomatic", p.cyclomatic);
        metrics.put("max_nesting", p.maxNesting);
        metrics.put("paragraphs", p.paragraphs.size());
        metrics.put("unreachable_paragraphs", p.unreachable);
        List<Long> existing = jdbc.queryForList("SELECT id FROM program WHERE member_id = ? AND program_id = ?", Long.class, memberId, p.programId);
        if (!existing.isEmpty()) {
            jdbc.update("UPDATE program SET parent_id = ?, kind = ?, line_start = ?, line_end = ?, metrics = ?::jsonb WHERE id = ?", parent, p.kind, p.start.line(), p.lineEnd, json(metrics), existing.get(0));
            return existing.get(0);
        }
        return jdbc.queryForObject("INSERT INTO program (member_id, program_id, parent_id, kind, line_start, line_end, metrics) VALUES (?,?,?,?,?,?,?::jsonb) RETURNING id", Long.class,
                memberId, p.programId, parent, p.kind, p.start.line(), p.lineEnd, json(metrics));
    }

    private void writeProgram(ProgramFacts p, long programId, long memberId, String memberName, ExpandedSource src, Members mem) {
        Set<Key> keys = new HashSet<>();
        String name = p.programId;
        Key self = new Key("program", name, "");
        keys.add(self);
        Map<String, Long> paraIds = new HashMap<>();
        for (Para q : p.paragraphs) {
            keys.add(new Key("paragraph", q.name(), name));
            Long pid = jdbc.queryForObject("INSERT INTO paragraph (program_id, section, name, line_start, line_end, member_id, complexity, reachable) VALUES (?,?,?,?,?,?,?,?) RETURNING id", Long.class,
                    programId, q.section(), q.name(), q.start().line(), q.lineEnd(), mem.id(q.start()), q.complexity(), !p.unreachable.contains(q.name()));
            paraIds.put(q.name(), pid);
        }
        writeDataItems(p.dataItems, p.conditions, memberId, programId, mem);
        for (var d : p.dataItems) if (d.name() != null) keys.add(new Key("data_item", d.name(), name));
        for (Sql s : p.sql) {
            jdbc.update("INSERT INTO sql_stmt (program_id, paragraph_id, member_id, line, line_end, stmt_type, tables, columns, host_vars, text) VALUES (?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?)",
                    programId, paraIds.get(s.paragraph()), mem.id(s.loc()), s.loc().line(), s.lineEnd(), s.stmtType(), json(s.tables()), json(s.columns()), json(s.hostVars()), s.text());
            if (!s.stmtType().equals("DECLARE TABLE")) s.tables().forEach(t -> keys.add(new Key("table", bare(t), "")));
        }
        for (Cics c : p.cics) {
            jdbc.update("INSERT INTO cics_cmd (program_id, paragraph_id, member_id, line, command, resource_kind, resource_name, attrs) VALUES (?,?,?,?,?,?,?,?::jsonb)",
                    programId, paraIds.get(c.paragraph()), mem.id(c.loc()), c.loc().line(), c.command(), c.resourceKind(), c.resourceName(), json(c.attrs()));
            if (c.resourceKind() != null && c.resourceResolved() || "file".equals(c.resourceKind())) keys.add(new Key(c.resourceKind(), c.resourceName(), ""));
            if ("program".equals(c.resourceKind()) && !c.resourceResolved()) {
                unresolved(programId, mem.id(c.loc()), c.resourceName(), c.loc().line(), "CICS " + c.command() + " through a variable with no literal", p);
            }
        }
        for (Call c : p.calls) {
            if (c.kind() == CallKind.DYNAMIC_UNRESOLVED) {
                unresolved(programId, mem.id(c.loc()), c.target(), c.loc().line(), "CALL through a variable with no VALUE and no MOVE of a literal", p);
            } else {
                keys.add(new Key("program", c.target(), ""));
            }
        }
        p.mq.forEach(q -> { if (q.queue() != null) keys.add(new Key("queue", q.queue(), "")); });
        p.files.forEach(f -> keys.add(new Key("file", f.name(), name)));
        p.fileIo.forEach(f -> keys.add(new Key("file", f.file(), name)));
        for (Perform f : p.performs) {
            keys.add(new Key("paragraph", f.target(), name));
            if (f.paragraph() != null) keys.add(new Key("paragraph", f.paragraph(), name));
        }
        for (Move m : p.moves) {
            if (!m.literal()) {
                keys.add(new Key("data_item", m.from(), name));
                keys.add(new Key("data_item", m.to(), name));
            }
        }
        for (var c : src.copies()) keys.add(new Key("copybook", c.name().toUpperCase(Locale.ROOT), ""));

        Map<Key, Long> ids = nodes.ensure(keys);
        nodes.setMember(ids.get(self), memberId);

        long me = ids.get(self);
        List<Edge> edges = new ArrayList<>();
        for (Call c : p.calls) {
            if (c.kind() == CallKind.DYNAMIC_UNRESOLVED) continue;
            Map<String, Object> a = new HashMap<>();
            a.put("mode", c.kind() == CallKind.STATIC ? "static" : "dynamic");
            if (c.variable() != null) a.put("variable", c.variable());
            a.put("using", c.using());
            edges.add(new Edge(me, ids.get(new Key("program", c.target(), "")), "CALLS", programId, mem.id(c.loc()), c.loc().line(), a));
        }
        for (Cics c : p.cics) {
            if (c.resourceKind() == null) continue;
            String type = cicsEdge(c.command(), c.resourceKind());
            if (type == null) continue;
            Long dst = ids.get(new Key(c.resourceKind(), c.resourceName(), ""));
            if (dst == null) continue; // A program held in a variable that never gets a literal: no honest edge exists.
            Map<String, Object> a = new HashMap<>();
            a.put("via", "CICS " + c.command());
            if (c.attrs().containsKey("COMMAREA")) a.put("commarea", c.attrs().get("COMMAREA").replaceAll("\\(.*", "").strip().toUpperCase(Locale.ROOT));
            edges.add(new Edge(me, dst, type, programId, mem.id(c.loc()), c.loc().line(), a));
        }
        for (Sql s : p.sql) {
            String t = sqlEdge(s.stmtType());
            if (t == null) continue;
            for (String tb : s.tables()) edges.add(new Edge(me, ids.get(new Key("table", bare(tb), "")), t, programId, mem.id(s.loc()), s.loc().line(), Map.of()));
        }
        for (Mq q : p.mq) {
            if (q.queue() == null) continue;
            edges.add(new Edge(me, ids.get(new Key("queue", q.queue(), "")), q.verb().startsWith("MQPUT") ? "PUTS_QUEUE" : "GETS_QUEUE", programId, mem.id(q.loc()), q.loc().line(), Map.of()));
        }
        for (FileIo f : p.fileIo) {
            String t = f.verb().startsWith("READ") || f.verb().equals("START") || f.verb().equals("OPEN INPUT") ? "READS"
                    : f.verb().equals("OPEN I-O") ? "READS" : "WRITES";
            edges.add(new Edge(me, ids.get(new Key("file", f.file(), name)), t, programId, mem.id(f.loc()), f.loc().line(), Map.of("verb", f.verb())));
        }
        for (Perform f : p.performs) {
            if (f.paragraph() == null) {
                edges.add(new Edge(me, ids.get(new Key("paragraph", f.target(), name)), "PERFORMS", programId, mem.id(f.loc()), f.loc().line(), attrsThru(f)));
            } else {
                edges.add(new Edge(ids.get(new Key("paragraph", f.paragraph(), name)), ids.get(new Key("paragraph", f.target(), name)), "PERFORMS", programId, mem.id(f.loc()), f.loc().line(), attrsThru(f)));
            }
        }
        for (var c : src.copies()) {
            edges.add(new Edge(me, ids.get(new Key("copybook", c.name().toUpperCase(Locale.ROOT), "")), "COPIES", programId, memberId, c.at().line(), Map.of()));
        }
        for (Move m : p.moves) {
            if (m.literal()) continue;
            edges.add(new Edge(ids.get(new Key("data_item", m.from(), name)), ids.get(new Key("data_item", m.to(), name)), "MOVES_TO", programId, mem.id(m.loc()), m.loc().line(), Map.of("op", m.kind())));
        }
        for (var d : p.dataItems) {
            if (d.redefines() == null || d.name() == null) continue;
            Long target = ids.get(new Key("data_item", d.redefines(), name));
            if (target != null) edges.add(new Edge(ids.get(new Key("data_item", d.name(), name)), target, "REDEFINES", programId, mem.id(d.loc()), d.loc().line(), Map.of()));
        }
        for (var f : p.files) {
            if (f.assign() != null) {
                // The ASSIGN name is the DD name, which is how a JCL step's dataset reaches this file.
                edges.add(new Edge(me, ids.get(new Key("file", f.name(), name)), "DECLARES_FILE", programId, mem.id(f.loc()), f.loc().line(), Map.of("assign", f.assign())));
            }
        }
        writeEdges(edges);
        // Kept for the linking step, which joins LINKAGE parameters across CALL USING.
        jdbc.update("UPDATE program SET metrics = metrics || ?::jsonb WHERE id = ?", json(Map.of("using", p.usingList)), programId);
    }

    private void unresolved(long programId, long memberId, String expr, int line, String reason, ProgramFacts p) {
        jdbc.update("INSERT INTO unresolved_call (program_id, member_id, target_expr, line, candidates, reason) VALUES (?,?,?,?,?::jsonb,?)",
                programId, memberId, expr, line, json(p.dynamicCandidates.getOrDefault(expr, List.of())), reason);
    }

    private static Map<String, Object> attrsThru(Perform f) {
        return f.thru() == null ? Map.of() : Map.of("thru", f.thru());
    }

    private void writeEdges(List<Edge> edges) {
        if (edges.isEmpty()) return;
        jdbc.batchUpdate("INSERT INTO graph_edge (src_id, dst_id, type, program_id, member_id, line, attrs) VALUES (?,?,?,?,?,?,?::jsonb)", edges, 500, (ps, e) -> {
            ps.setLong(1, e.src());
            ps.setLong(2, e.dst());
            ps.setString(3, e.type());
            ps.setLong(4, e.programId());
            ps.setLong(5, e.memberId());
            ps.setInt(6, e.line());
            ps.setString(7, json(e.attrs()));
        });
    }

    private static String bare(String table) {
        int dot = table.lastIndexOf('.');
        return dot >= 0 ? table.substring(dot + 1) : table;
    }

    private static String sqlEdge(String t) {
        return switch (t) {
            case "SELECT", "DECLARE CURSOR" -> "SELECTS";
            case "INSERT" -> "INSERTS";
            case "UPDATE" -> "UPDATES";
            case "DELETE" -> "DELETES";
            default -> null;
        };
    }

    private static String cicsEdge(String cmd, String kind) {
        return switch (kind) {
            case "program" -> switch (cmd) { case "LINK" -> "LINKS"; case "XCTL" -> "XCTLS"; default -> null; };
            case "map" -> switch (cmd) { case "SEND" -> "SENDS_MAP"; case "RECEIVE" -> "RECEIVES_MAP"; default -> null; };
            case "transaction" -> switch (cmd) { case "RETURN", "START" -> "STARTS_TRANSACTION"; default -> null; };
            case "file" -> switch (cmd) {
                case "READ", "READNEXT", "READPREV", "STARTBR" -> "READS";
                case "WRITE", "REWRITE", "DELETE" -> "WRITES";
                default -> null;
            };
            case "queue" -> switch (cmd) {
                case "WRITEQ" -> "PUTS_QUEUE";
                case "READQ" -> "GETS_QUEUE";
                default -> null;
            };
            default -> null;
        };
    }

    void writeDataItems(List<DataItem> items, List<Condition> conds, long defaultOwner, Long programId, Members mem) {
        if (items.isEmpty()) return;
        List<Long> ids = jdbc.queryForList("SELECT nextval('data_item_id_seq') FROM generate_series(1, ?)", Long.class, items.size());
        Map<String, Long> byName = new HashMap<>();
        for (DataItem d : items) if (d.name() != null) byName.putIfAbsent(d.name(), ids.get(d.index()));
        jdbc.batchUpdate("INSERT INTO data_item (id, owner_member_id, program_id, parent_id, level, name, pic, usage, occurs, occurs_max, odo_ref, redefines_id, redefines_name, \"offset\", length, line, section) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                items, 500, (ps, d) -> {
                    ps.setLong(1, ids.get(d.index()));
                    ps.setLong(2, mem == null ? defaultOwner : mem.id(d.loc()));
                    ps.setObject(3, programId);
                    ps.setObject(4, d.parent() < 0 ? null : ids.get(d.parent()));
                    ps.setInt(5, d.level());
                    ps.setString(6, d.name());
                    ps.setString(7, d.pic());
                    ps.setString(8, d.usage());
                    ps.setObject(9, d.occursMin());
                    ps.setObject(10, d.occursMax());
                    ps.setString(11, d.odoRef());
                    ps.setObject(12, d.redefines() == null ? null : byName.get(d.redefines()));
                    ps.setString(13, d.redefines());
                    ps.setInt(14, d.offset());
                    ps.setInt(15, d.length());
                    ps.setInt(16, d.loc().line());
                    ps.setString(17, d.section());
                });
        for (Condition c : conds) {
            if (c.dataItem() < 0) continue;
            jdbc.update("INSERT INTO condition_value (data_item_id, name, \"values\", line) VALUES (?,?,?::jsonb,?)", ids.get(c.dataItem()), c.name(), json(c.values()), c.loc().line());
        }
    }

    /** A copybook member's own layout: items with no owning program, plus its copybook row. */
    void writeCopybook(long memberId, String name, CobolFacts facts) {
        jdbc.update("INSERT INTO copybook (member_id, name) VALUES (?,?)", memberId, name.toUpperCase(Locale.ROOT));
        for (ProgramFacts p : facts.programs) {
            writeDataItems(p.dataItems, p.conditions, memberId, null, null);
        }
        for (String f : facts.features) {
            jdbc.update("INSERT INTO dialect_feature (member_id, feature) VALUES (?,?)", memberId, f);
        }
        Long nodeId = nodes.ensure(List.of(new Key("copybook", name.toUpperCase(Locale.ROOT), ""))).values().iterator().next();
        nodes.setMember(nodeId, memberId);
    }
}
