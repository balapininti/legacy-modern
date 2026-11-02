package com.modtoolkit.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.modtoolkit.parsers.cobol.*;
import com.modtoolkit.parsers.normalize.NormalizedMember;
import com.modtoolkit.parsers.normalize.Normalizer;
import com.modtoolkit.parsers.normalize.SourceOptions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Two independent parsers over the same real code: where they disagree, one of them is wrong. */
class ParserDifferentialIT {
    static final Path CORPUS = Path.of("..").toAbsolutePath().normalize().resolve(".cache/corpora/carddemo/app");

    private static TreeSet<String> facts(CobolFacts f) {
        TreeSet<String> s = new TreeSet<>();
        for (CobolFacts.ProgramFacts p : f.programs) {
            s.add("program " + p.programId);
            p.calls.forEach(c -> s.add("call " + c.target() + "@" + c.loc().line()));
            p.paragraphs.forEach(q -> s.add("para " + q.name() + "@" + q.start().line()));
            p.performs.forEach(q -> s.add("perform " + q.target() + "@" + q.loc().line()));
            p.gotos.forEach(q -> s.add("goto " + q.target() + "@" + q.loc().line()));
            p.sql.forEach(q -> s.add("sql " + q.stmtType() + "@" + q.loc().line()));
            p.cics.forEach(q -> s.add("cics " + q.command() + "@" + q.loc().line()));
            p.dataItems.forEach(d -> {
                if (d.name() != null) s.add("item " + d.name() + " " + d.offset() + "+" + d.length() + "@" + d.loc().line());
            });
        }
        return s;
    }

    @Test
    void proLeapAndKoopaAgreeOnEveryCardDemoProgram() throws Exception {
        assumeTrue(Files.isDirectory(CORPUS.resolve("cbl")));
        CopybookLibrary lib = new CopybookLibrary();
        try (Stream<Path> s = Files.walk(CORPUS)) {
            for (Path f : s.filter(p -> p.toString().toLowerCase().matches(".*\\.(cpy|dcl)")).sorted().toList()) {
                NormalizedMember m = Normalizer.normalize(Files.readAllBytes(f), SourceOptions.AUTO);
                lib.add(InventoryScanner.memberName(f), CORPUS.relativize(f).toString(), m.cobolLines());
            }
        }
        List<Path> programs;
        try (Stream<Path> s = Files.list(CORPUS.resolve("cbl"))) {
            programs = s.filter(p -> p.toString().toLowerCase().matches(".*\\.cbl")).sorted().toList();
        }
        int compared = 0, facts = 0;
        List<String> report = new ArrayList<>();
        for (Path f : programs) {
            NormalizedMember m = Normalizer.normalize(Files.readAllBytes(f), SourceOptions.AUTO);
            ExpandedSource e = CopybookResolver.expand(InventoryScanner.memberName(f), m.cobolLines(), lib, false);
            TreeSet<String> a = facts(new ProLeapCobolParser().parse(e));
            TreeSet<String> b = facts(new KoopaCobolParser().parse(e));
            facts += a.size();
            TreeSet<String> onlyA = new TreeSet<>(a), onlyB = new TreeSet<>(b);
            onlyA.removeAll(b);
            onlyB.removeAll(a);
            if (!onlyA.isEmpty() || !onlyB.isEmpty()) report.add(f.getFileName() + " only proleap " + onlyA + " only koopa " + onlyB);
            compared++;
        }
        assertThat(compared).isEqualTo(programs.size()).isGreaterThan(25);
        assertThat(facts).isGreaterThan(5000);
        assertThat(report).as(String.join("\n", report)).isEmpty();
    }
}
