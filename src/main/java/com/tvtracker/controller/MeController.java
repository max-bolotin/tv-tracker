package com.tvtracker.controller;

import com.tvtracker.model.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class MeController {

    @GetMapping("/me")
    public ResponseEntity<CurrentUser> me(@AuthenticationPrincipal OAuth2User principal) {
        if (principal == null) {
            return ResponseEntity.status(401).build();
        }

        Map<String, Object> attrs = principal.getAttributes();
        String id = String.valueOf(attrs.getOrDefault("sub", principal.getName()));
        String name = String.valueOf(attrs.getOrDefault("name", principal.getName()));
        String email = attrs.get("email") == null ? null : String.valueOf(attrs.get("email"));
        String picture = attrs.get("picture") == null ? null : String.valueOf(attrs.get("picture"));

        return ResponseEntity.ok(new CurrentUser(id, name, email, picture));
    }
}
