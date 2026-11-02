package com.modtoolkit.discovery.modern;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Spring-aware facts from Java source. Source is parsed, never compiled or run. */
final class JavaExtractor {
    private static final Map<String, String> MAPPINGS = Map.of("GetMapping", "GET", "PostMapping", "POST", "PutMapping", "PUT", "DeleteMapping", "DELETE", "PatchMapping", "PATCH");
    private static final Set<String> STEREOTYPES = Set.of("RestController", "Controller", "Service", "Repository", "Component", "Configuration", "Entity");
    private static final Set<String> HTTP_CLIENT_CALLS = Set.of("getForObject", "getForEntity", "postForObject", "postForEntity", "exchange", "uri");

    private final JavaParser parser = new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21).setAttributeComments(false));

    record Result(List<ModernUnit> units, String error) {}

    Result extract(String path, String source, String hash) {
        ParseResult<CompilationUnit> r = parser.parse(source);
        if (r.getResult().isEmpty() || (!r.isSuccessful() && r.getResult().get().getTypes().isEmpty())) {
            return new Result(List.of(), r.getProblems().isEmpty() ? "unparseable" : r.getProblem(0).getVerboseMessage());
        }
        CompilationUnit cu = r.getResult().get();
        String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString() + ".").orElse("");
        List<ModernUnit> out = new ArrayList<>();
        for (TypeDeclaration<?> t : cu.getTypes()) type(t, pkg, path, hash, out);
        return new Result(out, null);
    }

    private void type(TypeDeclaration<?> t, String prefix, String path, String hash, List<ModernUnit> out) {
        String fq = prefix + t.getNameAsString();
        Map<String, Object> attrs = new LinkedHashMap<>();
        List<String> annotations = new ArrayList<>();
        for (AnnotationExpr a : t.getAnnotations()) annotations.add(a.getNameAsString());
        attrs.put("annotations", annotations);
        for (String s : annotations) if (STEREOTYPES.contains(s)) attrs.put("stereotype", s);
        String kind = "java_class";
        if (annotations.contains("Entity")) {
            kind = "entity";
            String table = annotationValue(t.getAnnotationByName("Table"), "name").orElse(t.getNameAsString());
            attrs.put("table", table.toUpperCase(Locale.ROOT));
            attrs.put("columns", columns(t));
        } else if (isDto(t, fq)) {
            kind = "dto";
            attrs.put("fields", fields(t));
        }
        attrs.put("outbound", outbound(t));
        out.add(new ModernUnit(kind, fq, path, kind + " " + t.getNameAsString(), line(t, true), line(t, false), hash, attrs));

        if (annotations.contains("RestController") || annotations.contains("Controller")) endpoints(t, fq, path, hash, out);
        batch(t, fq, path, hash, out);
        for (var m : t.getMembers()) {
            if (m instanceof TypeDeclaration<?> inner) type(inner, fq + ".", path, hash, out);
        }
    }

    private static boolean isDto(TypeDeclaration<?> t, String fq) {
        String n = t.getNameAsString();
        return n.matches(".*(Dto|DTO|Request|Response)$") || fq.contains(".dto.") || (t instanceof RecordDeclaration && !t.getAnnotationByName("Service").isPresent() && fq.contains(".model."));
    }

    private void endpoints(TypeDeclaration<?> t, String fq, String path, String hash, List<ModernUnit> out) {
        String base = annotationValue(t.getAnnotationByName("RequestMapping"), "value").orElse("");
        for (MethodDeclaration m : t.getMethods()) {
            for (AnnotationExpr a : m.getAnnotations()) {
                String verb = MAPPINGS.get(a.getNameAsString());
                if (verb == null && a.getNameAsString().equals("RequestMapping")) {
                    verb = annotationValue(Optional.of(a), "method").map(v -> v.replaceAll(".*\\.", "")).orElse("ANY");
                }
                if (verb == null) continue;
                String sub = annotationValue(Optional.of(a), "value").orElse(annotationValue(Optional.of(a), "path").orElse(""));
                String url = (base + "/" + sub).replaceAll("/+", "/").replaceAll("(?<=.)/$", "");
                Map<String, Object> attrs = new LinkedHashMap<>();
                attrs.put("method", verb);
                attrs.put("path", url);
                attrs.put("handler", fq + "#" + m.getNameAsString());
                attrs.put("response", m.getType().asString());
                List<String> params = new ArrayList<>();
                for (Parameter p : m.getParameters()) {
                    if (p.getAnnotationByName("RequestBody").isPresent()) attrs.put("request", p.getType().asString());
                    params.add(p.getType().asString() + " " + p.getNameAsString());
                }
                attrs.put("params", params);
                out.add(new ModernUnit("endpoint", verb + " " + url, path, m.getDeclarationAsString(false, false, false), line(m, true), line(m, false), hash, attrs));
            }
        }
    }

    /** Spring Batch Job and Step beans, named by their builders where a literal says so. */
    private void batch(TypeDeclaration<?> t, String fq, String path, String hash, List<ModernUnit> out) {
        for (MethodDeclaration m : t.getMethods()) {
            if (!m.getAnnotationByName("Bean").isPresent()) continue;
            String ret = m.getType().asString();
            if (!ret.equals("Job") && !ret.equals("Step")) continue;
            String builder = ret + "Builder";
            String name = m.findAll(ObjectCreationExpr.class).stream()
                    .filter(c -> c.getType().getNameAsString().equals(builder) && !c.getArguments().isEmpty() && c.getArgument(0).isStringLiteralExpr())
                    .map(c -> c.getArgument(0).asStringLiteralExpr().asString()).findFirst().orElse(m.getNameAsString());
            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("bean", m.getNameAsString());
            attrs.put("config", fq);
            attrs.put("bean_type", ret);
            out.add(new ModernUnit("batch_job", name, path, ret + " " + m.getNameAsString(), line(m, true), line(m, false), hash, attrs));
        }
    }

    private static List<String> outbound(TypeDeclaration<?> t) {
        List<String> urls = new ArrayList<>();
        for (MethodCallExpr c : t.findAll(MethodCallExpr.class)) {
            if (!HTTP_CLIENT_CALLS.contains(c.getNameAsString()) || c.getArguments().isEmpty()) continue;
            Expression a = c.getArgument(0);
            if (a instanceof StringLiteralExpr s && (s.asString().startsWith("/") || s.asString().startsWith("http"))) urls.add(s.asString());
        }
        return urls;
    }

    private static Map<String, String> columns(TypeDeclaration<?> t) {
        Map<String, String> m = new LinkedHashMap<>();
        for (FieldDeclaration f : t.getFields()) {
            String col = annotationValue(f.getAnnotationByName("Column"), "name").orElse(null);
            f.getVariables().forEach(v -> m.put(v.getNameAsString(), col));
        }
        return m;
    }

    private static List<String> fields(TypeDeclaration<?> t) {
        List<String> names = new ArrayList<>();
        if (t instanceof RecordDeclaration r) r.getParameters().forEach(p -> names.add(p.getNameAsString()));
        for (FieldDeclaration f : t.getFields()) f.getVariables().forEach(v -> names.add(v.getNameAsString()));
        return names;
    }

    private static Optional<String> annotationValue(Optional<AnnotationExpr> a, String key) {
        if (a.isEmpty()) return Optional.empty();
        AnnotationExpr x = a.get();
        if (x instanceof SingleMemberAnnotationExpr s && key.equals("value")) return Optional.of(literal(s.getMemberValue()));
        if (x instanceof NormalAnnotationExpr n) {
            for (MemberValuePair p : n.getPairs()) {
                if (p.getNameAsString().equals(key)) return Optional.of(literal(p.getValue()));
            }
        }
        return Optional.empty();
    }

    private static String literal(Expression e) {
        if (e.isStringLiteralExpr()) return e.asStringLiteralExpr().asString();
        if (e.isArrayInitializerExpr() && !e.asArrayInitializerExpr().getValues().isEmpty()) return literal(e.asArrayInitializerExpr().getValues().get(0));
        return e.toString();
    }

    private static int line(com.github.javaparser.ast.Node n, boolean begin) {
        return n.getRange().map(r -> begin ? r.begin.line : r.end.line).orElse(1);
    }
}
