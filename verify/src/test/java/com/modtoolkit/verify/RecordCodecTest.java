package com.modtoolkit.verify;

import static org.assertj.core.api.Assertions.assertThat;

import com.modtoolkit.parsers.copybook.CopybookLayout.Item;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Spec: decoder round trips for COMP-3, binary, signed zoned and ODO records. */
class RecordCodecTest {
    private static Item it(int lvl, String n, String pic, String usage) {
        return new Item(lvl, n, pic, usage, false, 1, null);
    }

    private static final RecordLayout LAYOUT = RecordLayout.of(List.of(
            it(1, "REC", null, null), it(5, "ID", "X(4)", null), it(5, "PACK", "S9(7)V99", "COMP-3"), it(5, "PACKU", "9(3)", "COMP-3"),
            it(5, "BIN2", "S9(4)", "COMP"), it(5, "BIN4", "S9(9)", "COMP"), it(5, "BIN8", "S9(18)", "BINARY"), it(5, "UBIN", "9(4)", "COMP-5"),
            it(5, "ZONED", "S9(5)V99", null), it(5, "UZONED", "9(3)", null), it(5, "TXT", "X(3)", null)));

    @ParameterizedTest
    @ValueSource(strings = {"cp037", "cp1047", "utf-8"})
    void roundTripsEveryNumericFormInEachEncoding(String enc) {
        RecordCodec c = new RecordCodec(enc);
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("ID", "A1B2");
        v.put("PACK", new BigDecimal("-1234567.89"));
        v.put("PACKU", new BigDecimal("999"));
        v.put("BIN2", new BigDecimal("-1234"));
        v.put("BIN4", new BigDecimal("123456789"));
        v.put("BIN8", new BigDecimal("-123456789012345678"));
        v.put("UBIN", new BigDecimal("9999"));
        v.put("ZONED", new BigDecimal("-12345.67"));
        v.put("UZONED", new BigDecimal("42"));
        v.put("TXT", "abc");
        Map<String, Object> back = c.decode(c.encode(v, LAYOUT), LAYOUT);
        assertThat(back).containsEntry("ID", "A1B2").containsEntry("TXT", "abc");
        for (String k : List.of("PACK", "PACKU", "BIN2", "BIN4", "BIN8", "UBIN", "ZONED", "UZONED")) {
            assertThat((BigDecimal) back.get(k)).as(k).isEqualByComparingTo((BigDecimal) v.get(k));
        }
        assertThat(((BigDecimal) back.get("PACK")).scale()).isEqualTo(2);
    }

    /** Bytes written out by hand from the IBM manuals, so this does not just test encode against decode. */
    @Test
    void decodesHandBuiltIbmBytes() {
        RecordLayout l = RecordLayout.of(List.of(it(1, "R", null, null), it(5, "P", "S9(3)V99", "COMP-3"), it(5, "N", "S9(3)", "COMP-3"), it(5, "U", "9(3)", "COMP-3"), it(5, "B", "S9(4)", "COMP"), it(5, "Z", "S9(3)", null), it(5, "Y", "S9(3)", null)));
        byte[] rec = {0x12, 0x34, 0x5C, 0x12, 0x3D, 0x12, 0x3F, (byte) 0xFF, (byte) 0xFE, (byte) 0xF1, (byte) 0xF2, (byte) 0xD3, (byte) 0xF1, (byte) 0xF2, (byte) 0xC3};
        Map<String, Object> m = new RecordCodec("cp037").decode(rec, l);
        assertThat((BigDecimal) m.get("P")).isEqualByComparingTo("123.45");
        assertThat((BigDecimal) m.get("N")).isEqualByComparingTo("-123");
        assertThat((BigDecimal) m.get("U")).isEqualByComparingTo("123");
        assertThat((BigDecimal) m.get("B")).isEqualByComparingTo("-2");
        assertThat((BigDecimal) m.get("Z")).isEqualByComparingTo("-123");
        assertThat((BigDecimal) m.get("Y")).isEqualByComparingTo("123");
    }

    @Test
    void readsAsciiOverpunchAndGnuCobolNegativeZone() {
        RecordLayout l = RecordLayout.of(List.of(it(1, "R", null, null), it(5, "A", "S9(3)V99", null), it(5, "B", "S9(3)V99", null), it(5, "C", "S9(3)V99", null), it(5, "D", "S9(3)V99", null)));
        byte[] rec = "1234{1234}1234J0000y".getBytes(Charset.forName("ISO-8859-1"));
        Map<String, Object> m = new RecordCodec("utf-8").decode(rec, l);
        assertThat((BigDecimal) m.get("A")).isEqualByComparingTo("123.40");
        assertThat((BigDecimal) m.get("B")).isEqualByComparingTo("-123.40");
        assertThat((BigDecimal) m.get("C")).isEqualByComparingTo("-123.41");
        assertThat((BigDecimal) m.get("D")).isEqualByComparingTo("-0.09");
    }

    @Test
    void occursDependingOnReturnsOnlyTheLiveOccurrences() {
        Item tab = new Item(5, "TAB", null, null, false, 5, null);
        RecordLayout base = RecordLayout.of(List.of(it(1, "R", null, null), it(5, "N", "9(2)", null), tab, it(10, "AMT", "S9(3)", "COMP-3"), it(10, "CD", "X", null)));
        List<RecordLayout.Field> f = new java.util.ArrayList<>(base.fields());
        RecordLayout.Field t = f.get(2);
        f.set(2, new RecordLayout.Field(t.name(), t.level(), t.parent(), t.pic(), t.usage(), false, false, t.offset(), t.length(), t.occurs(), "N", null, true));
        RecordLayout odo = new RecordLayout(f);
        assertThat(odo.length()).isEqualTo(2 + 5 * 3);
        byte[] rec = new byte[17];
        java.util.Arrays.fill(rec, (byte) 0x40);
        rec[0] = (byte) 0xF0;
        rec[1] = (byte) 0xF2;
        rec[2] = 0x12;
        rec[3] = 0x3C;
        rec[4] = (byte) 0xC1;
        rec[5] = 0x45;
        rec[6] = 0x6D;
        rec[7] = (byte) 0xC2;
        rec[8] = 0x77; // Garbage past the live occurrences must not surface.
        Map<String, Object> m = new RecordCodec("cp037").decode(rec, odo);
        @SuppressWarnings("unchecked") List<Map<String, Object>> rows = (List<Map<String, Object>>) m.get("TAB");
        assertThat(rows).hasSize(2);
        assertThat((BigDecimal) rows.get(0).get("AMT")).isEqualByComparingTo("123");
        assertThat(rows.get(0)).containsEntry("CD", "A");
        assertThat((BigDecimal) rows.get(1).get("AMT")).isEqualByComparingTo("-456");
    }

    @Test
    void redefinesAreDecodedAsEveryView() {
        RecordLayout l = RecordLayout.of(List.of(it(1, "R", null, null), it(5, "RAW", "X(4)", null), new Item(5, "NUM", "9(4)", null, false, 1, "RAW")));
        Map<String, Object> m = new RecordCodec("utf-8").decode("1234".getBytes(), l);
        assertThat(m).containsEntry("RAW", "1234");
        assertThat((BigDecimal) m.get("NUM")).isEqualByComparingTo("1234");
    }

    @Test
    void junkInANumericFieldShowsAsNullRatherThanAWrongNumber() {
        RecordLayout l = RecordLayout.of(List.of(it(1, "R", null, null), it(5, "N", "9(3)", null)));
        assertThat(new RecordCodec("utf-8").decode("1x3".getBytes(), l).get("N")).isNull();
    }
}
