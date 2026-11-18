package de.ostms.evils.ebics.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/** Carries the AdminUser's tenant (hostId, null for SUPERADMIN) through Spring Security's
 * authentication so TenantContextFilter can scope the rest of the request to it - replaces the
 * plain org.springframework.security.core.userdetails.User that AdminUserService used to build. */
public class EvilsUserDetails implements UserDetails {
    private final String username;
    private final String password;
    private final String role;
    private final boolean active;
    private final String hostId;
    private final boolean totpEnabled;
    private final String totpSecret;

    public EvilsUserDetails(String username, String password, String role, boolean active, String hostId,
                             boolean totpEnabled, String totpSecret) {
        this.username = username;
        this.password = password;
        this.role = role;
        this.active = active;
        this.hostId = hostId;
        this.totpEnabled = totpEnabled;
        this.totpSecret = totpSecret;
    }

    public String hostId() {
        return hostId;
    }

    public boolean isSuperadmin() {
        return hostId == null;
    }

    /** Read by TotpAuthenticationProvider to decide whether a login for this user must also
     * present a valid TOTP code. */
    public boolean isTotpEnabled() {
        return totpEnabled;
    }

    public String totpSecret() {
        return totpSecret;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return active;
    }
}
