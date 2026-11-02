package com.modtoolkit.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.modtoolkit.knowledge.KnowledgeReview;
import com.modtoolkit.knowledge.LinkMatcher;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The SME review queue. */
@RestController
@RequestMapping("/api")
@ConditionalOnWebApplication
public class ReviewController {
    public record StatusChange(String status) {}
    public record CoverageConfirm(long artifactId, String ruleId, String status, Long modernUnitId) {}

    private final KnowledgeReview review;
    private final LinkMatcher links;

    public ReviewController(KnowledgeReview review, LinkMatcher links) {
        this.review = review;
        this.links = links;
    }

    @GetMapping("/artifacts")
    public List<Map<String, Object>> artifacts(@RequestParam(required = false) String status, @RequestParam(required = false) String kind,
            @RequestParam(defaultValue = "50") int limit, @RequestParam(defaultValue = "0") int offset) {
        return review.queue(status, kind, Math.min(limit, 200), Math.max(offset, 0));
    }

    @PostMapping("/artifacts/{id}/status")
    @PreAuthorize("hasAnyRole('sme-reviewer','admin')")
    public Map<String, Object> setStatus(@PathVariable long id, @RequestBody StatusChange c, Principal who) {
        review.setStatus(id, c.status(), who.getName());
        return Map.of("id", id, "status", c.status());
    }

    @PutMapping("/artifacts/{id}/content")
    @PreAuthorize("hasAnyRole('sme-reviewer','admin')")
    public Map<String, Object> edit(@PathVariable long id, @RequestBody JsonNode content, Principal who) {
        review.edit(id, content.toString(), who.getName());
        return Map.of("id", id, "status", "reviewed");
    }

    @PostMapping("/rule-coverage")
    @PreAuthorize("hasAnyRole('developer','admin')")
    public Map<String, Object> confirm(@RequestBody CoverageConfirm c, Principal who) {
        if (!List.of("covered", "uncovered", "changed").contains(c.status())) throw new IllegalArgumentException("status must be covered, uncovered or changed");
        links.confirmCoverage(c.artifactId(), c.ruleId(), c.status(), c.modernUnitId(), who.getName());
        return Map.of("rule_id", c.ruleId(), "status", c.status());
    }
}
