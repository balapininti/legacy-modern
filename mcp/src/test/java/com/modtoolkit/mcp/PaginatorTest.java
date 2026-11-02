package com.modtoolkit.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** Review Focus 5: every page within the cap, and the pages join back to exactly the input. */
class PaginatorTest {
    private final ObjectMapper om = new ObjectMapper();
    private final Paginator p = new Paginator(om);

    private List<JsonNode> walk(List<?> items, List<Integer> sizes) throws Exception {
        List<JsonNode> all = new ArrayList<>();
        StringBuilder partial = new StringBuilder();
        String cursor = null;
        do {
            String page = p.page(items, cursor, "k");
            sizes.add(page.length());
            assertThat(page.length()).isLessThanOrEqualTo(Paginator.MAX_CHARS);
            JsonNode n = om.readTree(page);
            for (JsonNode it : n.get("items")) {
                if (it.has("partial")) {
                    assertThat(it.get("partial").get("offset").asInt()).isEqualTo(partial.length());
                    partial.append(it.get("text").asText());
                    if (it.get("partial").get("last").asBoolean()) {
                        all.add(om.readTree(partial.toString()));
                        partial.setLength(0);
                    }
                } else {
                    all.add(it);
                }
            }
            cursor = n.get("next_cursor").isNull() ? null : n.get("next_cursor").asText();
        } while (cursor != null);
        return all;
    }

    @Test
    void manySmallItemsPageWithoutLossOrDuplication() throws Exception {
        List<Map<String, Object>> items = IntStream.range(0, 3000).mapToObj(i -> Map.<String, Object>of("n", i, "text", "line " + i + " ".repeat(i % 40))).toList();
        List<Integer> sizes = new ArrayList<>();
        List<JsonNode> back = walk(items, sizes);
        assertThat(sizes.size()).isGreaterThan(1);
        assertThat(back).hasSize(3000);
        for (int i = 0; i < 3000; i++) assertThat(back.get(i).get("n").asInt()).isEqualTo(i);
    }

    @Test
    void anOversizedItemSplitsAndRejoinsExactly() throws Exception {
        String big = "é\"\\\n😀 quoted ".repeat(9000);
        List<Map<String, Object>> items = List.of(Map.of("n", 0, "text", "small"), Map.of("n", 1, "text", big), Map.of("n", 2, "text", "after"));
        List<JsonNode> back = walk(items, new ArrayList<>());
        assertThat(back).hasSize(3);
        assertThat(back.get(1).get("text").asText()).isEqualTo(big);
        assertThat(back.get(2).get("n").asInt()).isEqualTo(2);
    }

    @Test
    void emptyResultHasNoCursor() throws Exception {
        JsonNode n = om.readTree(p.page(List.of(), null, "k"));
        assertThat(n.get("items")).isEmpty();
        assertThat(n.get("next_cursor").isNull()).isTrue();
    }

    @Test
    void cursorIsBoundToItsCallAndGarbageIsAStructuredError() throws Exception {
        List<Integer> items = IntStream.range(0, 20000).boxed().toList();
        String c = om.readTree(p.page(items, null, "k1")).get("next_cursor").asText();
        assertThatThrownBy(() -> p.page(items, c, "k2")).isInstanceOfSatisfying(ToolError.class, e -> assertThat(e.code()).isEqualTo("BAD_CURSOR"));
        assertThatThrownBy(() -> p.page(items, "not a cursor!!", "k1")).isInstanceOfSatisfying(ToolError.class, e -> assertThat(e.code()).isEqualTo("BAD_CURSOR"));
    }
}
