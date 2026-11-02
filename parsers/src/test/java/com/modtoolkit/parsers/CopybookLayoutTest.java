package com.modtoolkit.parsers;

import static org.assertj.core.api.Assertions.assertThat;

import com.modtoolkit.parsers.copybook.CopybookLayout;
import com.modtoolkit.parsers.copybook.CopybookLayout.FieldLayout;
import com.modtoolkit.parsers.copybook.CopybookLayout.Item;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Sizes computed by hand from the IBM Enterprise COBOL Language Reference storage tables. */
class CopybookLayoutTest {
    private static Item it(int lvl, String name, String pic, String usage) {
        return new Item(lvl, name, pic, usage, false, 1, null);
    }

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
        "X(10),null,10", "9(5),null,5", "S9(5),null,5", "S9(5)V99,null,7", "ZZ9.99,null,6", "9(4)CR,null,6",
        "S9(4),COMP,2", "S9(5),COMP,4", "S9(9),COMP,4", "S9(10),COMP,8", "9(18),COMP-4,8", "S9(4),COMP-5,2", "9(4),BINARY,2",
        "S9(7),COMP-3,4", "S9(8),COMP-3,5", "9(1),COMP-3,1", "S9(5)V99,PACKED-DECIMAL,4",
        "null,COMP-1,4", "null,COMP-2,8", "null,POINTER,4", "N(3),null,6"})
    void elementaryWidths(String pic, String usage, int expected) {
        assertThat(CopybookLayout.compute(List.of(it(1, "A", pic, usage))).get(0).length()).isEqualTo(expected);
    }

    @Test
    void signSeparateAddsOneByte() {
        assertThat(CopybookLayout.compute(List.of(new Item(1, "A", "S9(5)", null, true, 1, null))).get(0).length()).isEqualTo(6);
    }

    @Test
    void groupOffsetsOccursAndInheritedUsage() {
        List<FieldLayout> l = CopybookLayout.compute(List.of(
                it(1, "REC", null, null),
                it(5, "ID", "X(4)", null),
                new Item(5, "TAB", null, null, false, 3, null),
                it(10, "AMT", "S9(7)", "COMP-3"),
                it(10, "CODE", "X", null),
                it(5, "LAST", "X(2)", null)));
        assertThat(l.get(2).offset()).isEqualTo(4);
        assertThat(l.get(2).length()).isEqualTo(5);
        assertThat(l.get(2).totalLength()).isEqualTo(15);
        assertThat(l.get(4).offset()).isEqualTo(8);
        assertThat(l.get(5).offset()).isEqualTo(19);
        assertThat(l.get(0).length()).isEqualTo(21);
    }

    @Test
    void redefinesOverlaysAndAddsNothing() {
        List<FieldLayout> l = CopybookLayout.compute(List.of(
                it(1, "REC", null, null),
                it(5, "A", "X(8)", null),
                new Item(5, "B", "9(8)", null, false, 1, "A"),
                new Item(5, "C", null, null, false, 1, "B"),
                it(10, "C1", "X(4)", null),
                it(10, "C2", "X(4)", null),
                it(5, "D", "X", null)));
        assertThat(l.get(2).offset()).isZero();
        assertThat(l.get(3).offset()).isZero();
        assertThat(l.get(5).offset()).isEqualTo(4);
        assertThat(l.get(6).offset()).isEqualTo(8);
        assertThat(l.get(0).length()).isEqualTo(9);
    }

    @Test
    void occursDependingOnIsSizedAtMaximum() {
        List<FieldLayout> l = CopybookLayout.compute(List.of(
                it(1, "REC", null, null),
                it(5, "N", "9(2)", null),
                new Item(5, "T", "X(5)", null, false, 10, null)));
        assertThat(l.get(0).length()).isEqualTo(52);
    }

    @Test
    void groupUsageIsInheritedByChildren() {
        List<FieldLayout> l = CopybookLayout.compute(List.of(it(1, "G", null, "COMP-3"), it(5, "A", "S9(7)", null), it(5, "B", "S9(3)", null)));
        assertThat(l.get(1).length()).isEqualTo(4);
        assertThat(l.get(0).length()).isEqualTo(6);
    }
}
