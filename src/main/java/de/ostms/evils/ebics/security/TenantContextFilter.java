package de.ostms.evils.ebics.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Populates TenantContext from the authenticated principal for the duration of the request - a
 * scoped admin/operator always sees only their own tenant, a SUPERADMIN (hostId == null) leaves
 * it unset so AdminController's effectiveHostId falls back to requiring an explicit
 * X-Tenant-Host-Id header. Registered after Spring Security's authentication filters, so the
 * SecurityContext is already populated by the time this runs. */
@Component
public class TenantContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.getPrincipal() instanceof EvilsUserDetails user
                    && user.hostId() != null) {
                TenantContext.set(user.hostId());
            }
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
