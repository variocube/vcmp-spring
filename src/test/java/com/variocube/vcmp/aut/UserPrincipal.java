package com.variocube.vcmp.aut;

import java.security.Principal;

/** A principal with value equality, as the authenticators of the tests return. */
public record UserPrincipal(String name) implements Principal {
    @Override
    public String getName() {
        return name;
    }
}
