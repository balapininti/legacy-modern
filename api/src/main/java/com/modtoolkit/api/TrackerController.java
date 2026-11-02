package com.modtoolkit.api;

import com.modtoolkit.core.migration.MigrationService;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tracker")
@ConditionalOnWebApplication
public class TrackerController {
    public record StageChange(String stage, String owner) {}
    public record Implementation(String status, String note) {}

    private final JdbcTemplate jdbc;
    private final MigrationService migration;

    public TrackerController(JdbcTemplate jdbc, MigrationService migration) {
        this.jdbc = jdbc;
        this.migration = migration;
    }

    private long id(String name) {
        try {
            return jdbc.queryForObject("SELECT id FROM program WHERE upper(program_id) = upper(?) AND parent_id IS NULL ORDER BY id LIMIT 1", Long.class, name);
        } catch (EmptyResultDataAccessException e) {
            throw new MigrationService.NotFound("no program named " + name);
        }
    }

    /** Sign-off is the SME's: nobody else moves a program to sme_signed_off. */
    @PostMapping("/{program}/stage")
    @PreAuthorize("hasAnyRole('admin','developer','sme-reviewer')")
    public Map<String, Object> stage(@PathVariable String program, @RequestBody StageChange c, org.springframework.security.core.Authentication a) {
        long pid = id(program);
        if (c.stage() != null) {
            boolean sme = a.getAuthorities().stream().anyMatch(x -> x.getAuthority().equals("ROLE_sme-reviewer") || x.getAuthority().equals("ROLE_admin"));
            if ("sme_signed_off".equals(c.stage()) && !sme) throw new org.springframework.security.access.AccessDeniedException("sign-off needs the SME role");
            migration.advance(pid, c.stage(), null);
        }
        if (c.owner() != null) {
            jdbc.update("INSERT INTO migration_status (program_id, stage) VALUES (?, 'analyzed') ON CONFLICT DO NOTHING", pid);
            jdbc.update("UPDATE migration_status SET owner = ?, updated_at = now() WHERE program_id = ?", c.owner(), pid);
        }
        return jdbc.queryForMap("SELECT stage, owner FROM migration_status WHERE program_id = ?", pid);
    }

    @PostMapping("/{program}/implementation")
    @PreAuthorize("hasAnyRole('admin','developer')")
    public Map<String, Object> implementation(@PathVariable String program, @RequestBody Implementation b, Principal who) {
        if (!List.of("implemented", "partial", "missing", "replaced_by_design").contains(b.status())) throw new IllegalArgumentException("status must be implemented, partial, missing or replaced_by_design");
        migration.setImplementation(id(program), b.status(), b.note(), who.getName());
        return Map.of("program", program, "status", b.status());
    }
}
