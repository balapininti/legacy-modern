package com.acme.card.web;

import com.acme.card.dto.AccountViewResponse;
import com.acme.card.service.AccountService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {
    private final AccountService service;

    public AccountController(AccountService service) {
        this.service = service;
    }

    @GetMapping("/{id}")
    public ResponseEntity<AccountViewResponse> view(@PathVariable long id) {
        return service.view(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }
}
