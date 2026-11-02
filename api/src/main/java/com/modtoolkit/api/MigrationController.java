package com.modtoolkit.api;

import com.modtoolkit.core.migration.MigrationService;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Leads decide: confirming a link or approving a deviation is an admin action, and is recorded with who did it. */
@RestController
@RequestMapping("/api")
@ConditionalOnWebApplication
public class MigrationController {
    private final MigrationService service;

    public MigrationController(MigrationService service) {
        this.service = service;
    }

    @GetMapping("/links")
    public List<Map<String, Object>> links(@RequestParam(required = false) String status) {
        return service.links(status);
    }

    @PostMapping("/links/{id}/confirm")
    @PreAuthorize("hasRole('admin')")
    public Map<String, Object> confirm(@PathVariable long id, Principal who) {
        service.confirmLink(id, who.getName());
        return Map.of("id", id, "status", "confirmed");
    }

    @PostMapping("/links/{id}/reject")
    @PreAuthorize("hasRole('admin')")
    public Map<String, Object> reject(@PathVariable long id, Principal who) {
        service.rejectLink(id, who.getName());
        return Map.of("id", id, "status", "rejected");
    }

    @GetMapping("/deviations")
    public List<Map<String, Object>> deviations(@RequestParam(required = false) String status, @RequestParam(required = false) String program) {
        return service.deviations(status, program);
    }

    @PostMapping("/deviations/{id}/approve")
    @PreAuthorize("hasRole('admin')")
    public Map<String, Object> approve(@PathVariable long id, Principal who) {
        service.decideDeviation(id, true, who.getName());
        return Map.of("id", id, "status", "approved");
    }

    @PostMapping("/deviations/{id}/reject")
    @PreAuthorize("hasRole('admin')")
    public Map<String, Object> rejectDeviation(@PathVariable long id, Principal who) {
        service.decideDeviation(id, false, who.getName());
        return Map.of("id", id, "status", "rejected");
    }
}
