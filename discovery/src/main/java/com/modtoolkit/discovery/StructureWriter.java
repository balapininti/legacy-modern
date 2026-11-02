package com.modtoolkit.discovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modtoolkit.discovery.NodeStore.Key;
import com.modtoolkit.parsers.bms.BmsParser;
import com.modtoolkit.parsers.csd.CsdParser;
import com.modtoolkit.parsers.ddl.DdlParser;
import com.modtoolkit.parsers.jcl.JclParser;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/** Stores JCL, BMS, DDL and CSD facts and their graph edges. */
final class StructureWriter {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final NodeStore nodes;

    StructureWriter(JdbcTemplate jdbc, NodeStore nodes) {
        this.jdbc = jdbc;
        this.nodes = nodes;
    }

    private static String json(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void edge(long src, long dst, String type, long member, int line, Map<String, ?> attrs) {
        jdbc.update("INSERT INTO graph_edge (src_id, dst_id, type, member_id, line, attrs) VALUES (?,?,?,?,?,?::jsonb)", src, dst, type, member, line, json(attrs));
    }

    void writeJobs(long memberId, List<JclParser.Job> jobs, Map<String, Long> memberByName) {
        for (JclParser.Job j : jobs) {
            String jobName = j.name() == null ? "UNNAMED" : j.name().toUpperCase();
            long jobId = jdbc.queryForObject("INSERT INTO jcl_job (member_id, name, line) VALUES (?,?,?) RETURNING id", Long.class, memberId, jobName, j.line());
            Set<Key> keys = new HashSet<>();
            Key jobKey = new Key("job", jobName, "");
            keys.add(jobKey);
            for (JclParser.Step s : j.steps()) {
                keys.add(stepKey(jobName, s));
                String pgm = effective(s);
                if (pgm != null) keys.add(new Key("program", pgm, ""));
                for (JclParser.Dd d : s.dds()) if (usableDsn(d)) keys.add(new Key("dataset", d.dsn(), ""));
            }
            Map<Key, Long> ids = nodes.ensure(keys);
            nodes.setMember(ids.get(jobKey), memberId);
            for (String missing : j.missingProcs()) {
                jdbc.update("INSERT INTO dialect_feature (member_id, feature, line) VALUES (?,?,?)", memberId, "MISSING PROC " + missing, j.line());
            }
            for (JclParser.Step s : j.steps()) {
                Long srcMember = memberByName.getOrDefault(s.member(), memberId);
                long stepRow = jdbc.queryForObject("INSERT INTO jcl_step (job_id, seq, name, pgm, proc, cond, params, line, source_member_id) VALUES (?,?,?,?,?,?,?::jsonb,?,?) RETURNING id", Long.class,
                        jobId, s.seq(), s.name(), effective(s), s.proc(), s.ifCond() != null ? (s.cond() == null ? "" : s.cond() + " ") + "IF " + s.ifCond() : s.cond(), json(s.params()), s.line(), srcMember);
                long stepNode = ids.get(stepKey(jobName, s));
                edge(stepNode, ids.get(jobKey), "RUNS_IN", memberId, j.line(), Map.of("seq", s.seq()));
                String pgm = effective(s);
                if (pgm != null) edge(ids.get(new Key("program", pgm, "")), stepNode, "RUNS_IN", srcMember, s.line(), Map.of("job", jobName));
                for (JclParser.Dd d : s.dds()) {
                    jdbc.update("INSERT INTO jcl_dd (step_id, dd_name, dsn, disp, gdg_rel, attrs, line) VALUES (?,?,?,?,?,?::jsonb,?)", stepRow, d.name(), d.dsn(), d.disp(), d.gdgRel(), json(d.attrs()), d.line());
                    if (usableDsn(d)) {
                        Map<String, Object> a = new java.util.HashMap<>();
                        a.put("dd", d.name());
                        a.put("disp", d.disp() == null ? "" : d.disp());
                        if (d.gdgRel() != null) a.put("gdg_rel", d.gdgRel());
                        edge(stepNode, ids.get(new Key("dataset", d.dsn(), "")), "USES_DATASET", memberByName.getOrDefault(d.member(), memberId), d.line(), a);
                    }
                }
            }
        }
    }

    private static Key stepKey(String job, JclParser.Step s) {
        return new Key("step", (s.name() == null ? "STEP" + s.seq() : s.name().toUpperCase()) + "#" + s.seq(), job);
    }

    private static String effective(JclParser.Step s) {
        String ims = JclParser.imsProgram(s);
        return ims != null ? ims : s.pgm();
    }

    /** Temporary and symbolic datasets name nothing shared between steps. */
    private static boolean usableDsn(JclParser.Dd d) {
        return d.dsn() != null && !d.dsn().startsWith("&") && !d.dsn().contains("&") && !d.dsn().startsWith("*.");
    }

    void writeBms(long memberId, List<BmsParser.BmsMap> maps) {
        for (BmsParser.BmsMap m : maps) {
            long id = jdbc.queryForObject("INSERT INTO bms_map (member_id, mapset, name, line) VALUES (?,?,?,?) RETURNING id", Long.class, memberId, m.mapset() == null ? "" : m.mapset(), m.name(), m.line());
            for (BmsParser.Field f : m.fields()) {
                jdbc.update("INSERT INTO bms_field (map_id, name, \"row\", col, length, attrs, line) VALUES (?,?,?,?,?,?::jsonb,?)", id, f.name(), f.row(), f.col(), f.length(), json(f.attrs()), f.line());
            }
            long node = nodes.ensure(List.of(new Key("map", m.name(), ""))).values().iterator().next();
            nodes.setMember(node, memberId);
        }
    }

    void writeDdl(long memberId, List<DdlParser.Table> tables) {
        for (DdlParser.Table t : tables) {
            long id = jdbc.queryForObject("INSERT INTO db2_table (name, schema, member_id, line) VALUES (?,?,?,?) RETURNING id", Long.class, t.name(), t.schema(), memberId, t.line());
            for (DdlParser.Column c : t.columns()) {
                jdbc.update("INSERT INTO db2_column (table_id, name, type, nullable, key_pos, line) VALUES (?,?,?,?,?,?)", id, c.name(), c.type(), c.nullable(), c.keyPos(), c.line());
            }
            long node = nodes.ensure(List.of(new Key("table", t.name(), ""))).values().iterator().next();
            nodes.setMember(node, memberId);
        }
    }

    void writeCsd(long memberId, List<CsdParser.Definition> defs) {
        for (CsdParser.Definition d : defs) {
            switch (d.kind()) {
                case "TRANSACTION" -> {
                    String pgm = d.attrs().get("PROGRAM");
                    Set<Key> keys = new HashSet<>();
                    Key tx = new Key("transaction", d.name(), "");
                    keys.add(tx);
                    if (pgm != null) keys.add(new Key("program", pgm.toUpperCase(), ""));
                    Map<Key, Long> ids = nodes.ensure(keys);
                    nodes.setMember(ids.get(tx), memberId);
                    if (pgm != null) edge(ids.get(new Key("program", pgm.toUpperCase(), "")), ids.get(tx), "RUNS_IN", memberId, d.line(), Map.of("via", "CSD"));
                }
                case "FILE" -> {
                    String dsn = d.attrs().get("DSNAME");
                    if (dsn == null || dsn.isBlank()) break;
                    Map<Key, Long> ids = nodes.ensure(List.of(new Key("file", d.name(), ""), new Key("dataset", dsn.toUpperCase(), "")));
                    edge(ids.get(new Key("file", d.name(), "")), ids.get(new Key("dataset", dsn.toUpperCase(), "")), "USES_DATASET", memberId, d.line(), Map.of("via", "CSD"));
                }
                default -> { }
            }
        }
    }

    static List<String> linesOf(String text) {
        return new ArrayList<>(List.of(text.split("\n", -1)));
    }
}
