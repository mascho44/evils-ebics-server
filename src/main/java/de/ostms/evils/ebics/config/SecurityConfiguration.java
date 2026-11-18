package de.ostms.evils.ebics.config;

import de.ostms.evils.ebics.security.TenantContextFilter;
import de.ostms.evils.ebics.security.TotpAuthenticationProvider;
import de.ostms.evils.ebics.security.TotpLoginFilter;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;

import java.util.List;

/**
 * Spring Security setup: BCrypt password encoder, an authentication manager that supports the optional
 * TOTP second factor, and the filter chain with session login, the tenant context filter and the rules for
 * which paths are public (EBICS endpoint, landing page, sandbox signup, health) and which need a session.
 */
@Configuration
public class SecurityConfiguration {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    // Explicit AuthenticationManager containing ONLY our TotpAuthenticationProvider - without
    // this, Spring Boot's autoconfiguration would ALSO build a plain DaoAuthenticationProvider
    // from the AdminUserService/PasswordEncoder beans and add it alongside ours; ProviderManager
    // accepts the first provider that succeeds, so that second provider (which knows nothing about
    // TOTP) would let a 2FA-enabled account log in with just its password. Declaring this bean
    // suppresses that autoconfiguration entirely.
    @Bean AuthenticationManager authenticationManager(TotpAuthenticationProvider totpAuthenticationProvider) {
        return new ProviderManager(List.of(totpAuthenticationProvider));
    }

    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http, TenantContextFilter tenantContextFilter,
                                                   AuthenticationManager authenticationManager) throws Exception {
        // TotpLoginFilter's own constructor already restricts matching to POST /login - do NOT
        // additionally call setFilterProcessesUrl("/login") here: it internally overwrites that
        // restriction back to Spring's method-agnostic default matcher (matches GET too), which
        // caused a real production incident where a stray GET /login was treated as a login
        // attempt with empty credentials (see TotpLoginFilter's constructor comment).
        TotpLoginFilter loginFilter = new TotpLoginFilter(authenticationManager);
        SavedRequestAwareAuthenticationSuccessHandler successHandler = new SavedRequestAwareAuthenticationSuccessHandler();
        successHandler.setDefaultTargetUrl("/admin.html");
        successHandler.setAlwaysUseDefaultTargetUrl(true);
        loginFilter.setAuthenticationSuccessHandler(successHandler);
        loginFilter.setAuthenticationFailureHandler(new SimpleUrlAuthenticationFailureHandler("/login-error.html"));

        return http.csrf(csrf -> csrf.disable())
                .authenticationManager(authenticationManager)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/login", "/login.html", "/error").permitAll()
                        // SUPERADMIN-only tenant management, restricted to role ADMIN like the
                        // other admin-only endpoints below - the finer-grained "must actually be
                        // a superadmin, not just any tenant's ADMIN" check happens inside
                        // TenantController itself, since Spring Security's URL rules can't
                        // express "hostId == null" on the principal.
                        .requestMatchers("/admin/api/tenants/**").hasRole("ADMIN")
                        .requestMatchers("/admin/api/tenant-requests/**").hasRole("ADMIN")
                        // Public self-service tenant signup (TenantSignupController) - reachable
                        // without login by design, not an oversight; nothing is actually created
                        // until a SUPERADMIN approves it via /admin/api/tenant-requests above.
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/tenant-signup").permitAll()
                        .requestMatchers("/admin/api/crypto/**").hasRole("ADMIN")
                        .requestMatchers("/admin/api/events/**").hasRole("ADMIN")
                        .requestMatchers("/admin/api/test-scenarios/**").hasRole("ADMIN")
                        .requestMatchers("/admin/api/admin-users/**").hasRole("ADMIN")
                        // "/" is the public landing page (static/index.html) - only the admin
                        // dashboard itself (static/admin.html) needs a login, not the root.
                        .requestMatchers("/admin/api/**", "/admin.html").hasAnyRole("ADMIN", "OPERATOR")
                        .requestMatchers("/ebicsweb").permitAll()
                        // Any other GET (e.g. a client/scanner probing "/ebicsveu" or some other
                        // guessed path) reveals nothing sensitive - let it through so
                        // UnknownPathController's error page can answer with a friendly
                        // EBICS-branded 404 instead of the security layer's raw
                        // {"status":401,...} JSON, which a real EBICS client's operator would
                        // have no context to interpret. This also covers the public landing page
                        // (/) and the signup form (/signup.html).
                        .requestMatchers(HttpMethod.GET, "/**").permitAll()
                        .anyRequest().authenticated())
                // Custom login page (static/login.html, adds the optional TOTP field) instead of
                // Spring Security's auto-generated one - an unauthenticated request to a protected
                // page is redirected there. Login POST processing itself is handled by
                // TotpLoginFilter below, not formLogin()'s own filter, since the built-in one has
                // no way to read the extra "totp" form field.
                .exceptionHandling(ex -> ex.authenticationEntryPoint(new LoginUrlAuthenticationEntryPoint("/login.html")))
                .logout(Customizer.withDefaults())
                .addFilterAt(loginFilter, UsernamePasswordAuthenticationFilter.class)
                // Runs once the request is authenticated, so it can read the EvilsUserDetails
                // principal and populate TenantContext for the rest of the request - see
                // TenantContextFilter.
                .addFilterAfter(tenantContextFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
