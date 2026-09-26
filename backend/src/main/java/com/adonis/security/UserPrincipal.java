package com.adonis.security;

import java.security.Principal;

public record UserPrincipal(
        String id,
        String email,
        String name
) implements Principal {

    @Override
    public String getName() {
        return email;
    }
}
