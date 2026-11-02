package com.modtoolkit.rulebook;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Learns conventions from the code that exists. Everything it reports comes with a count from the files it read. */
public final class ConventionAnalyzer {
    private static final Set<String> SKIP = Set.of(".git", "target", "build", "node_modules", "dist", ".angular");
    private static final Pattern PACKAGE = Pattern.compile("(?m)^package\\s+([\\w.]+);");
    private static final Pattern CLASS = Pattern.compile("(?m)^\\s*(?:public\\s+)?(?:abstract\\s+|final\\s+)?(class|interface|record|enum)\\s+(\\w+)");

    private ConventionAnalyzer() {}

    public static Conventions analyze(Path repo, MappingRules rules) throws IOException {
        Conventions c = new Conventions();
        List<Path> java = files(repo, ".java");
        List<Path> ts = files(repo, ".ts");
        Map<String, Integer> packages = new TreeMap<>(), suffix = new TreeMap<>();
        int records = 0, dtoClasses = 0, mapstruct = 0, mappers = 0, advice = 0, customEx = 0, slf4j = 0, lombokLog = 0, jul = 0, jpa = 0, jdbc = 0, entities = 0, ctor = 0, fieldInj = 0;
        Map<Path, String> texts = new LinkedHashMap<>();
        for (Path f : java) {
            String t = Files.readString(f, StandardCharsets.ISO_8859_1);
            texts.put(f, t);
            Matcher p = PACKAGE.matcher(t);
            String pkg = p.find() ? p.group(1) : "";
            packages.merge(pkg, 1, Integer::sum);
            Matcher cl = CLASS.matcher(t);
            if (cl.find()) {
                String name = cl.group(2);
                Matcher s = Pattern.compile("(Controller|Service|Repository|Mapper|Dto|DTO|Request|Response|Entity|Config|Configuration|Exception|Job|Calculator|Client)$").matcher(name);
                if (s.find()) suffix.merge(s.group(1), 1, Integer::sum);
                if (name.matches(".*(Dto|DTO|Request|Response)$")) {
                    if ("record".equals(cl.group(1))) records++; else dtoClasses++;
                }
                if (name.endsWith("Exception")) customEx++;
            }
            if (t.contains("org.mapstruct")) mapstruct++;
            if (t.contains("Mapper ") || t.contains("Mapper{")) mappers++;
            if (t.contains("@ControllerAdvice") || t.contains("@RestControllerAdvice")) advice++;
            if (t.contains("org.slf4j")) slf4j++;
            if (t.contains("@Slf4j")) lombokLog++;
            if (t.contains("java.util.logging")) jul++;
            if (t.contains("JpaRepository") || t.contains("CrudRepository")) jpa++;
            if (t.contains("JdbcTemplate")) jdbc++;
            if (t.contains("@Entity")) entities++;
            if (t.contains("@Autowired") && Pattern.compile("@Autowired\\s+(private|protected)").matcher(t).find()) fieldInj++;
            if (t.contains("private final ")) ctor++;
        }
        if (!packages.isEmpty()) {
            String root = commonPrefix(packages.keySet().stream().filter(s -> !s.isEmpty()).toList());
            if (!root.isEmpty()) c.packageLayout.add("Root package `" + root + "`.");
            List<String> subs = packages.entrySet().stream().filter(e -> e.getKey().startsWith(root)).sorted((a, b) -> b.getValue() - a.getValue()).limit(10)
                    .map(e -> "`" + (e.getKey().equals(root) ? "." : e.getKey().substring(root.length()).replaceFirst("^\\.", "")) + "` (" + e.getValue() + " files)").toList();
            if (subs.size() > 1) c.packageLayout.add("Organized by layer: " + String.join(", ", subs) + ".");
        }
        if (!suffix.isEmpty()) {
            c.naming.add("Class suffixes in use: " + suffix.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).map(e -> e.getKey() + " (" + e.getValue() + ")").collect(Collectors.joining(", ")) + ".");
        }
        if (records > 0 && records >= dtoClasses) c.dtoAndMapper.add("DTOs are Java records (" + records + " records, " + dtoClasses + " classes).");
        else if (dtoClasses > 0) c.dtoAndMapper.add("DTOs are classes (" + dtoClasses + " classes, " + records + " records).");
        if (mapstruct > 0) c.dtoAndMapper.add("Mappers use MapStruct (" + mapstruct + " files).");
        else if (mappers > 0) c.dtoAndMapper.add("Mappers are hand-written classes (" + mappers + " files).");
        if (advice > 0) c.errorHandling.add("Errors go through @ControllerAdvice (" + advice + " files).");
        if (customEx > 0) c.errorHandling.add(customEx + " custom exception classes.");
        if (lombokLog > 0) c.logging.add("Lombok @Slf4j (" + lombokLog + " files).");
        else if (slf4j > 0) c.logging.add("SLF4J loggers (" + slf4j + " files).");
        else if (jul > 0) c.logging.add("java.util.logging (" + jul + " files).");
        if (jpa > 0) c.persistence.add("Spring Data repositories (" + jpa + " files), " + entities + " JPA entities.");
        if (jdbc > 0) c.persistence.add("JdbcTemplate (" + jdbc + " files).");
        if (fieldInj > ctor) c.naming.add("Field injection with @Autowired is the local habit (" + fieldInj + " files).");
        else if (ctor > 0) c.naming.add("Constructor injection (" + ctor + " files with final fields).");
        angular(c, ts);
        conflicts(c, rules, texts, repo);
        return c;
    }

    private static void angular(Conventions c, List<Path> ts) throws IOException {
        int components = 0, standalone = 0, ngModule = 0, signals = 0, http = 0;
        Map<String, Integer> serviceSuffix = new TreeMap<>();
        for (Path f : ts) {
            String t = Files.readString(f, StandardCharsets.ISO_8859_1);
            if (t.contains("@Component")) components++;
            if (t.contains("standalone: true")) standalone++;
            if (t.contains("@NgModule")) ngModule++;
            if (t.contains("signal(") || t.contains("inject(")) signals++;
            if (t.contains("HttpClient")) http++;
            if (t.contains("@Injectable")) {
                Matcher m = Pattern.compile("export class (\\w+)").matcher(t);
                if (m.find()) {
                    Matcher s = Pattern.compile("(Service|Api|Client|Store)$").matcher(m.group(1));
                    if (s.find()) serviceSuffix.merge(s.group(1), 1, Integer::sum);
                }
            }
        }
        if (components == 0) return;
        c.angular.add(standalone >= ngModule ? "Standalone components (" + standalone + " of " + components + ")." : "NgModule based (" + ngModule + " modules, " + components + " components).");
        if (!serviceSuffix.isEmpty()) c.angular.add("Injectable naming: " + serviceSuffix.entrySet().stream().map(e -> "*" + e.getKey() + " (" + e.getValue() + ")").collect(Collectors.joining(", ")) + ".");
        if (http > 0) c.angular.add("HTTP calls live in injectables using HttpClient (" + http + " files).");
    }

    /** A shipped rule that the code contradicts goes to a lead, with evidence. It is never applied silently. */
    private static void conflicts(Conventions c, MappingRules rules, Map<Path, String> texts, Path repo) {
        for (MappingRules.Rule r : rules.all()) {
            if (r.conflict() == null) continue;
            Pattern p = Pattern.compile(r.conflict().regex());
            List<String> evidence = new ArrayList<>();
            for (Map.Entry<Path, String> e : texts.entrySet()) {
                String[] lines = e.getValue().split("\n", -1);
                for (int i = 0; i < lines.length && evidence.size() < 5; i++) {
                    if (p.matcher(lines[i]).find()) evidence.add(repo.relativize(e.getKey()) + ":" + (i + 1) + ": " + lines[i].strip());
                }
            }
            if (evidence.isEmpty()) continue;
            Conventions.RuleConflict x = new Conventions.RuleConflict();
            x.ruleId = r.id();
            x.message = r.conflict().message();
            x.evidence = evidence;
            x.keepExistingNote = r.conflict().keepExistingNote();
            c.conflicts.add(x);
        }
    }

    private static String commonPrefix(List<String> pkgs) {
        if (pkgs.isEmpty()) return "";
        String[] first = pkgs.get(0).split("\\.");
        int n = first.length;
        for (String p : pkgs) {
            String[] a = p.split("\\.");
            int k = 0;
            while (k < n && k < a.length && a[k].equals(first[k])) k++;
            n = k;
        }
        return String.join(".", java.util.Arrays.copyOf(first, n));
    }

    private static List<Path> files(Path repo, String ext) throws IOException {
        if (!Files.isDirectory(repo)) return List.of();
        try (Stream<Path> s = Files.walk(repo)) {
            return s.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(ext) && !p.toString().endsWith(".spec.ts") && !p.toString().endsWith(".d.ts")).filter(p -> {
                for (Path part : repo.relativize(p)) if (SKIP.contains(part.toString())) return false;
                return true;
            }).sorted().toList();
        }
    }
}
