package com.tvtracker.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;

public final class CurrentUserContext {
    private CurrentUserContext() {
    }

    public static String currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return "default";
        }

        if (authentication instanceof OAuth2AuthenticationToken oauthToken) {
            return extractUserId(oauthToken.getPrincipal());
        }

        if (authentication.getPrincipal() instanceof OAuth2User oauthUser) {
            return extractUserId(oauthUser);
        }

        return authentication.getName();
    }

    private static String extractUserId(OAuth2User oauthUser) {
        Object sub = oauthUser.getAttributes().get("sub");
        if (sub != null) {
            return String.valueOf(sub);
        }
        return oauthUser.getName();
    }
}
