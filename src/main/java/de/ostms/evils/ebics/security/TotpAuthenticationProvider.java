package de.ostms.evils.ebics.security;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Replaces Spring Security's default DaoAuthenticationProvider: same username/password check via
 * PasswordEncoder, plus - if the loaded AdminUser has 2FA enabled - a required, valid TOTP code
 * (see TotpAuthenticationToken/TotpLoginFilter for how the code gets here). Deliberately throws
 * the same generic BadCredentialsException for a wrong password, a missing/wrong TOTP code, or an
 * unknown username alike, so a login failure never reveals which factor was wrong or whether 2FA
 * is even enabled for that account. */
@Component
public class TotpAuthenticationProvider implements AuthenticationProvider {

    private final UserDetailsService userDetailsService;
    private final PasswordEncoder passwordEncoder;
    private final TotpService totpService;

    public TotpAuthenticationProvider(UserDetailsService userDetailsService, PasswordEncoder passwordEncoder,
                                       TotpService totpService) {
        this.userDetailsService = userDetailsService;
        this.passwordEncoder = passwordEncoder;
        this.totpService = totpService;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        String username = authentication.getName();
        String rawPassword = String.valueOf(authentication.getCredentials());
        String totpCode = authentication instanceof TotpAuthenticationToken t ? t.totpCode() : null;

        EvilsUserDetails user;
        try {
            user = (EvilsUserDetails) userDetailsService.loadUserByUsername(username);
        } catch (UsernameNotFoundException e) {
            // Same message/timing shape as a wrong password - see class comment.
            throw new BadCredentialsException("Bad credentials");
        }

        if (!passwordEncoder.matches(rawPassword, user.getPassword())) {
            throw new BadCredentialsException("Bad credentials");
        }
        if (!user.isEnabled()) {
            throw new DisabledException("Disabled");
        }
        if (user.isTotpEnabled() && !totpService.verify(user.totpSecret(), totpCode)) {
            throw new BadCredentialsException("Bad credentials");
        }

        UsernamePasswordAuthenticationToken result =
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
        result.setDetails(authentication.getDetails());
        return result;
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
