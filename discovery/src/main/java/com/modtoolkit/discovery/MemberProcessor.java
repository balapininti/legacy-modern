package com.modtoolkit.discovery;

import com.modtoolkit.parsers.bms.BmsParser;
import com.modtoolkit.parsers.cobol.*;
import com.modtoolkit.parsers.csd.CsdParser;
import com.modtoolkit.parsers.ddl.DdlParser;
import com.modtoolkit.parsers.jcl.JclParser;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/** Parses one member and returns a writer action, so the AST never outlives this method. */
final class MemberProcessor {
    private static final String WRAP_DATA = "       IDENTIFICATION DIVISION.\n       PROGRAM-ID. COPYBOOK-WRAP.\n       DATA DIVISION.\n       WORKING-STORAGE SECTION.\n";
    private static final String WRAP_PROC = "       IDENTIFICATION DIVISION.\n       PROGRAM-ID. COPYBOOK-WRAP.\n       PROCEDURE DIVISION.\n";

    record Row(long id, String path, String name, String type, String format, String text) {}

    /** What to record for a member: its status, its error, and the database work. */
    record Result(long id, String status, String error, Integer errorLine, String parser, Runnable write) {}

    private final ResilientParser parser;
    private final CopybookLibrary lib;
    private final Map<String, Long> memberIds;
    private final FactWriter writer;
    private final StructureWriter structure;
    private final Map<String, JclParser.ProcBody> procs;
    private final Map<String, Long> procMemberIds;

    MemberProcessor(List<CobolParser> chain, Duration timeout, CopybookLibrary lib, Map<String, Long> memberIds, FactWriter writer,
            StructureWriter structure, Map<String, JclParser.ProcBody> procs, Map<String, Long> procMemberIds) {
        this.parser = new ResilientParser(chain, timeout);
        this.lib = lib;
        this.memberIds = memberIds;
        this.writer = writer;
        this.structure = structure;
        this.procs = procs;
        this.procMemberIds = procMemberIds;
    }

    Result process(Row r) {
        return switch (r.type()) {
            case "cobol" -> cobol(r);
            case "copybook" -> copybook(r);
            case "jcl" -> jcl(r);
            case "proc" -> new Result(r.id(), "parsed", null, null, "jcl", () -> {}); // Stored expanded, in each job that uses it.
            case "bms" -> {
                var maps = BmsParser.parse(lines(r));
                yield new Result(r.id(), "parsed", null, null, "bms", () -> structure.writeBms(r.id(), maps));
            }
            case "ddl" -> {
                var tables = DdlParser.parse(r.text());
                yield new Result(r.id(), "parsed", null, null, "ddl", () -> structure.writeDdl(r.id(), tables));
            }
            case "csd" -> {
                var defs = CsdParser.parse(lines(r));
                yield new Result(r.id(), "parsed", null, null, "csd", () -> structure.writeCsd(r.id(), defs));
            }
            default -> new Result(r.id(), "inventoried", null, null, null, () -> {});
        };
    }

    private static List<String> lines(Row r) {
        return Arrays.asList(r.text().split("\n", -1));
    }

    private Result jcl(Row r) {
        List<String> l = lines(r);
        List<JclParser.Job> jobs = JclParser.parse(r.name(), l, procs::get);
        List<CsdParser.Definition> inlineCsd = r.text().toUpperCase(java.util.Locale.ROOT).contains("DEFINE TRANSACTION") || r.text().toUpperCase(java.util.Locale.ROOT).contains("DEFINE FILE") ? CsdParser.parse(l) : List.of();
        return new Result(r.id(), "parsed", null, null, "jcl", () -> {
            structure.writeJobs(r.id(), jobs, procMemberIds);
            structure.writeCsd(r.id(), inlineCsd);
        });
    }

    static List<String> cobolLines(String text, String format) {
        List<String> lines = Arrays.asList(text.split("\n", -1));
        if (!"fixed".equals(format)) return lines;
        List<String> out = new ArrayList<>(lines.size());
        for (String l : lines) {
            String cut = l.length() > 72 ? l.substring(0, 72) : l;
            out.add(cut.length() <= 6 ? "" : "      " + cut.substring(6).stripTrailing());
        }
        return out;
    }

    private Result cobol(Row r) {
        boolean free = !"fixed".equals(r.format());
        ExpandedSource src = CopybookResolver.expand(r.name(), cobolLines(r.text(), r.format()), lib, free);
        ResilientParser.Outcome o = parser.parse(src);
        if (!o.ok()) {
            return new Result(r.id(), "failed", o.error(), o.errorAt() == null ? null : o.errorAt().line(), null, () -> {});
        }
        return new Result(r.id(), "parsed", null, null, o.parser(), () -> writer.writeProgramMember(r.id(), r.name(), src, o.facts(), lib, memberIds));
    }

    /** Copybooks are fragments, so each is parsed inside a minimal wrapper; origins still point at its own lines. */
    private Result copybook(Row r) {
        boolean free = !"fixed".equals(r.format());
        List<String> own = cobolLines(r.text(), r.format());
        // Placeholders such as :TAG: or (TESTVAR1) are not legal until a REPLACING fills them. A bare :TAG: is safe to
        // neutralize at once; (NAME) can be a legitimate subscript, so it is only tried after the plain forms fail.
        List<String> tagged = own.stream().map(l -> l.replaceAll(":([A-Za-z0-9-]+):", "$1")).toList();
        List<String> parens = tagged.stream().map(l -> l.replaceAll("\\(([A-Za-z][A-Za-z0-9-]*)\\)", "$1")).toList();
        ResilientParser.Outcome first = null;
        for (List<String> body : List.of(tagged, parens)) {
            for (String wrap : List.of(WRAP_DATA, WRAP_PROC)) {
                List<String> head = List.of(wrap.split("\n"));
                List<String> lines = new ArrayList<>(head);
                List<Loc> origins = new ArrayList<>();
                head.forEach(h -> origins.add(new Loc(r.name(), 1)));
                for (int i = 0; i < body.size(); i++) {
                    lines.add(body.get(i));
                    origins.add(new Loc(r.name(), i + 1));
                }
                if (wrap == WRAP_PROC) {
                    // A statement fragment needs no period of its own: the COPYing sentence ends it.
                    lines.add("           .");
                    origins.add(new Loc(r.name(), Math.max(1, body.size())));
                }
                ExpandedSource src = new ExpandedSource(r.name(), free, lines, origins, List.of(), List.of(), List.of());
                ResilientParser.Outcome o = parser.parse(src);
                if (o.ok()) {
                    CobolFacts f = o.facts();
                    return new Result(r.id(), "parsed", null, null, o.parser(), () -> writer.writeCopybook(r.id(), r.name(), f));
                }
                if (first == null) first = o;
            }
        }
        return new Result(r.id(), "failed", first.error(), first.errorAt() == null ? null : first.errorAt().line(), null,
                () -> writer.writeCopybook(r.id(), r.name(), new CobolFacts()));
    }

    static void record(JdbcTemplate jdbc, Result r) {
        FactWriter.clearMember(jdbc, r.id());
        r.write().run();
        jdbc.update("UPDATE member SET parse_status = ?, parse_error = ?, updated_at = now() WHERE id = ?", r.status(),
                r.error() == null ? null : (r.errorLine() == null ? "" : "line " + r.errorLine() + ": ") + r.error(), r.id());
    }
}
