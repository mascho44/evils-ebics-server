package de.ostms.evils.ebics.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/** Same login form/endpoint as Spring Security's default (username + password), plus an optional
 * "totp" field - builds a TotpAuthenticationToken instead of the plain
 * UsernamePasswordAuthenticationToken so TotpAuthenticationProvider can see the code, if any, that
 * was submitted alongside the password. A user with 2FA disabled simply never fills it in. */
public class TotpLoginFilter extends UsernamePasswordAuthenticationFilter {

    public TotpLoginFilter(AuthenticationManager authenticationManager) {
        setAuthenticationManager(authenticationManager);
        // setFilterProcessesUrl("/login") alone matches ANY HTTP method, not just POST - a plain
        // GET /login (e.g. admin.html's client-side code redirecting there on a stale/expired
        // session) was being treated as a login ATTEMPT with empty username/password, which of
        // course fails and bounces to the error page - looking exactly like "login keeps failing"
        // even right after a genuinely successful login elsewhere. Restrict to POST, like the
        // login form itself always uses.
        setRequiresAuthenticationRequestMatcher(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/login"));
        // THE ACTUAL PRODUCTION INCIDENT (2026-09-26): built manually with `new` instead of via
        // HttpSecurity's formLogin() DSL, this filter never received the session-backed
        // SecurityContextRepository that DSL wires in automatically - AbstractAuthenticationProcessingFilter's
        // own default is RequestAttributeSecurityContextRepository, which only keeps the
        // authenticated context alive for the CURRENT request/response (long enough for the 302 to
        // /admin.html to happen), then discards it. Every login therefore "succeeded" (visible in
        // TotpAuthenticationProvider's diagnostic log) but the very next request was already
        // unauthenticated again, bouncing straight back to the login page - looking exactly like a
        // silently failing login, forever, no matter how many times the real, correct password was
        // entered. Setting this explicitly is what actually persists the authenticated session.
        setSecurityContextRepository(new HttpSessionSecurityContextRepository());
    }

    @Override
    public Authentication attemptAuthentication(HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response) {
        String username = obtainUsername(request);
        String password = obtainPassword(request);
        String totpCode = request.getParameter("totp");
        username = username == null ? "" : username.trim();
        TotpAuthenticationToken authRequest = new TotpAuthenticationToken(username, password, totpCode);
        setDetails(request, authRequest);
        return this.getAuthenticationManager().authenticate(authRequest);
    }
}
