package com.modtoolkit.verify;

import com.modtoolkit.parsers.copybook.CopybookLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;

/** A record's fields with their offsets, as CopybookLayout computes them, for the decoder to walk. */
public final class RecordLayout {
    public record Field(String name, int level, int parent, String pic, String usage, boolean signSeparate, boolean signLeading, int offset, int length, int occurs, String odoRef, String redefines, boolean group) {}

    private final List<Field> fields;
    private final int length;

    public RecordLayout(List<Field> fields) {
        this.fields = fields;
        int max = 0;
        for (Field f : fields) if (f.parent() < 0) max = Math.max(max, f.offset() + f.length() * Math.max(1, f.occurs()));
        this.length = max;
    }

    public List<Field> fields() {
        return fields;
    }

    public int length() {
        return length;
    }

    public static RecordLayout of(List<CopybookLayout.Item> items) {
        List<CopybookLayout.FieldLayout> lay = CopybookLayout.compute(items);
        List<Field> out = new ArrayList<>();
        boolean[] parent = new boolean[lay.size()];
        lay.forEach(f -> {
            if (f.parent() >= 0) parent[f.parent()] = true;
        });
        for (int i = 0; i < lay.size(); i++) {
            var f = lay.get(i);
            var it = f.item();
            String usage = it.usage();
            int p = f.parent();
            while (usage == null && p >= 0) {
                usage = lay.get(p).item().usage();
                p = lay.get(p).parent();
            }
            out.add(new Field(it.name(), it.level(), f.parent(), it.pic(), usage, it.signSeparate(), false, f.offset(), f.length(), it.occursMax(), null, it.redefines(), parent[i]));
        }
        return new RecordLayout(out);
    }

    /** The layout discovery stored for a copybook. One 01 at a time, since a copybook can hold several records. */
    public static RecordLayout fromDatabase(JdbcTemplate jdbc, String copybook, String record) {
        List<java.util.Map<String, Object>> rows = jdbc.queryForList("""
                SELECT d.id, d.parent_id, d.level, d.name, d.pic, d.usage, d."offset", d.length, d.occurs_max, d.odo_ref, d.redefines_name
                FROM data_item d JOIN member m ON m.id = d.owner_member_id WHERE upper(m.name) = upper(?) AND m.type = 'copybook' AND d.program_id IS NULL ORDER BY d.id""", copybook);
        if (rows.isEmpty()) throw new IllegalArgumentException("no layout for copybook " + copybook + ": run discovery first");
        List<Field> out = new ArrayList<>();
        java.util.Map<Long, Integer> index = new java.util.HashMap<>();
        boolean in = record == null;
        for (var r : rows) {
            int level = ((Number) r.get("level")).intValue();
            if (level == 1 || level == 77) in = record == null || record.equalsIgnoreCase(String.valueOf(r.get("name")));
            if (!in) continue;
            long id = ((Number) r.get("id")).longValue();
            index.put(id, out.size());
            Number parent = (Number) r.get("parent_id");
            int pi = parent == null ? -1 : index.getOrDefault(parent.longValue(), -1);
            boolean hasKids = rows.stream().anyMatch(x -> x.get("parent_id") != null && ((Number) x.get("parent_id")).longValue() == id);
            String usage = (String) r.get("usage");
            int up = pi;
            while (usage == null && up >= 0) {
                usage = out.get(up).usage();
                up = out.get(up).parent();
            }
            Number occ = (Number) r.get("occurs_max");
            out.add(new Field((String) r.get("name"), level, pi, (String) r.get("pic"), usage == null ? null : usage.toUpperCase(Locale.ROOT), false, false,
                    ((Number) r.get("offset")).intValue(), ((Number) r.get("length")).intValue(), occ == null ? 1 : occ.intValue(), (String) r.get("odo_ref"), (String) r.get("redefines_name"), hasKids));
        }
        if (out.isEmpty()) throw new IllegalArgumentException("no record " + record + " in copybook " + copybook);
        return new RecordLayout(out);
    }
}
