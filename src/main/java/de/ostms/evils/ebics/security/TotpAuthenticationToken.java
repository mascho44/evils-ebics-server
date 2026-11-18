package de.ostms.evils.ebics.security;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

/** Carries the optional TOTP code alongside username/password through the authentication
 * pipeline - plain UsernamePasswordAuthenticationToken has no room for a third factor, and
 * AuthenticationProvider only ever sees the Authentication object, not the raw HttpServletRequest,
 * so the code has to be attached to the token itself (see TotpLoginFilter, which builds this from
 * the extra "totp" form field, and TotpAuthenticationProvider, which reads it back off). */
public class TotpAuthenticationToken extends UsernamePasswordAuthenticationToken {

    private final String totpCode;

    public TotpAuthenticationToken(Object principal, Object credentials, String totpCode) {
        super(principal, credentials);
        this.totpCode = totpCode;
    }

    public String totpCode() {
        return totpCode;
    }
}
