package com.modtoolkit.parsers.cobol;

import io.proleap.cobol.Cobol85Lexer;
import io.proleap.cobol.Cobol85Parser;
import io.proleap.cobol.asg.params.impl.CobolParserParamsImpl;
import io.proleap.cobol.preprocessor.CobolPreprocessor.CobolSourceFormatEnum;
import io.proleap.cobol.preprocessor.impl.CobolPreprocessorImpl;
import java.util.ArrayList;
import java.util.List;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.atn.PredictionMode;
import org.antlr.v4.runtime.tree.ErrorNode;
import org.antlr.v4.runtime.tree.ParseTreeListener;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.antlr.v4.runtime.tree.TerminalNode;

/** Primary parser: ProLeap's ANTLR grammar on line-preserved input, extracted by walking the parse tree. */
public final class ProLeapCobolParser implements CobolParser {
    private static final class Errors extends BaseErrorListener {
        int line = -1;
        String message;

        @Override
        public void syntaxError(Recognizer<?, ?> r, Object sym, int line, int col, String msg, RecognitionException e) {
            if (this.line < 0) {
                this.line = line;
                this.message = "line " + line + ":" + col + " " + msg;
            }
        }
    }

    /** Lets a timeout stop a parse: ANTLR never looks at the interrupt flag on its own. */
    private static final class InterruptCheck implements ParseTreeListener {
        @Override public void visitTerminal(TerminalNode n) {}
        @Override public void visitErrorNode(ErrorNode n) {}
        @Override public void exitEveryRule(ParserRuleContext c) {}
        @Override public void enterEveryRule(ParserRuleContext c) {
            if (Thread.currentThread().isInterrupted()) throw new CobolParseException("interrupted", null);
        }
    }

    @Override
    public String name() {
        return "proleap";
    }

    @Override
    public CobolFacts parse(ExpandedSource src) {
        ParserInput.Result in = ParserInput.prepare(src);
        String text = String.join("\n", in.lines()) + "\n";
        CobolParserParamsImpl params = new CobolParserParamsImpl();
        params.setCopyBookDirectories(List.of());
        params.setIgnoreSyntaxErrors(false);
        String pre;
        try {
            pre = new CobolPreprocessorImpl().process(text, CobolSourceFormatEnum.VARIABLE, params);
        } catch (RuntimeException e) {
            throw new CobolParseException("preprocessor: " + e.getMessage(), null);
        }
        int[] toExpanded = alignLines(pre, in.lines());
        Cobol85Parser.StartRuleContext tree = parseText(pre, toExpanded, src);
        CobolFacts facts = new FactExtractor(src, in.execs(), toExpanded).extract(tree);
        if (facts.programs.isEmpty()) throw new CobolParseException("no program found", null);
        return facts;
    }

    private Cobol85Parser.StartRuleContext parseText(String pre, int[] toExpanded, ExpandedSource src) {
        Errors errs = new Errors();
        Cobol85Parser p = parser(pre, errs);
        p.getInterpreter().setPredictionMode(PredictionMode.SLL); // Far faster; on any error, retried in full LL below.
        Cobol85Parser.StartRuleContext tree = p.startRule();
        if (errs.line >= 0) {
            errs = new Errors();
            p = parser(pre, errs);
            p.getInterpreter().setPredictionMode(PredictionMode.LL);
            tree = p.startRule();
        }
        if (errs.line >= 0) {
            int line = errs.line - 1 < toExpanded.length ? toExpanded[errs.line - 1] : errs.line;
            throw new CobolParseException(errs.message, src.origin(line));
        }
        return tree;
    }

    private static Cobol85Parser parser(String text, Errors errs) {
        Cobol85Lexer lexer = new Cobol85Lexer(CharStreams.fromString(text));
        lexer.removeErrorListeners();
        lexer.addErrorListener(errs);
        Cobol85Parser p = new Cobol85Parser(new CommonTokenStream(lexer));
        p.removeErrorListeners();
        p.addErrorListener(errs);
        p.addParseListener(new InterruptCheck());
        return p;
    }

    /**
     * ProLeap drops some lines (for example blank trailing ones), so each output line is mapped back by order: a
     * non-blank output line with the same code as the next non-blank input line lines up with it.
     */
    static int[] alignLines(String pre, List<String> input) {
        String[] out = pre.split("\n", -1);
        int[] map = new int[out.length];
        if (out.length == input.size() || out.length == input.size() + 1) {
            for (int i = 0; i < out.length; i++) map[i] = i + 1;
            return map;
        }
        int in = 0;
        for (int i = 0; i < out.length; i++) {
            String o = key(out[i]);
            if (o.isEmpty()) {
                map[i] = Math.min(in + 1, input.size());
                continue;
            }
            int k = in;
            while (k < input.size() && !key(input.get(k)).equals(o)) k++;
            if (k < input.size()) in = k;
            map[i] = Math.min(in + 1, input.size());
            if (k < input.size()) in++;
        }
        return map;
    }

    private static String key(String l) {
        return l.length() > 7 ? l.substring(7).replaceAll("\\s+", "") : "";
    }

    static List<String> linesOf(String s) {
        return new ArrayList<>(List.of(s.split("\n", -1)));
    }

    static void walk(ParseTreeListener l, ParserRuleContext t) {
        ParseTreeWalker.DEFAULT.walk(l, t);
    }
}
