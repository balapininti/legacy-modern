package com.modtoolkit.api;

import com.modtoolkit.core.migration.MigrationService;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** The same error shape as the MCP tools: code, message and hint, never a stack trace. */
@RestControllerAdvice
public class ApiErrors {
    private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);

    static ResponseEntity<Map<String, Object>> body(HttpStatus s, String code, String message, String hint) {
        return ResponseEntity.status(s).body(Map.of("error", Map.of("code", code, "message", String.valueOf(message), "hint", hint)));
    }

    @ExceptionHandler(MigrationService.NotFound.class)
    ResponseEntity<Map<String, Object>> notFound(MigrationService.NotFound e) {
        return body(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage(), "check the id");
    }

    @ExceptionHandler(MigrationService.Conflict.class)
    ResponseEntity<Map<String, Object>> conflict(MigrationService.Conflict e) {
        return body(HttpStatus.CONFLICT, "CONFLICT", e.getMessage(), "reload and try again");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, Object>> bad(IllegalArgumentException e) {
        return body(HttpStatus.BAD_REQUEST, "BAD_ARGUMENT", e.getMessage(), "see the API documentation");
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Map<String, Object>> denied(AccessDeniedException e) {
        return body(HttpStatus.FORBIDDEN, "FORBIDDEN", "your role does not allow this", "ask an admin");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> other(Exception e) {
        log.error("request failed", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", "the request failed unexpectedly", "the error is in the server log");
    }
}
