package com.variocube.vcmp;

/** Test access to package-private members of {@link VcmpClientAuthentication}. */
public final class VcmpClientAuthenticationAccess {
    private VcmpClientAuthenticationAccess() {
    }

    public static long refreshLeadMillis(VcmpClientAuthentication authentication, long expiresInMillis) {
        return authentication.refreshLeadMillis(expiresInMillis);
    }

    public static long refreshDelayMillis(VcmpClientAuthentication authentication, long expiresInMillis) {
        return authentication.refreshDelayMillis(expiresInMillis);
    }
}
