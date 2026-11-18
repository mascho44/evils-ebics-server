package de.ostms.evils.ebics.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import org.hibernate.annotations.ColumnDefault;

@Entity(name = "admin_users")
public class AdminUser {
    @Id private String username;
    /** Which tenant (Bank.hostId) this admin is scoped to. Null means SUPERADMIN: can manage
     * tenants themselves (see TenantController) and act on any tenant's data via an explicit
     * X-Tenant-Host-Id header, instead of being locked to one tenant like a normal admin. */
    private String hostId;
    private String displayName; private String passwordHash; private String role; private boolean active;
    /** TOTP (RFC 6238) secret, Base32-encoded - null until 2FA setup is started (see
     * TotpService/AdminUserService.beginTotpSetup). Never exposed via JSON (AdminUser is returned
     * directly from AdminUserController) - deliberately has no public getter, only totpEnabled(). */
    private String totpSecret;
    /** @ColumnDefault is required here, not optional: ddl-auto=update adding a plain NOT NULL
     * boolean column to admin_users (which already has rows in every real deployment) fails
     * outright in Postgres ("contains null values") - hit this exact failure in production on
     * 2026-09-26, breaking every login until the column was patched in by hand. The default lets
     * Postgres backfill existing rows with false (2FA off, their actual state) automatically. */
    @Column(nullable = false) @ColumnDefault("false") private boolean totpEnabled;
    protected AdminUser() { }
    public AdminUser(String hostId, String username, String displayName, String passwordHash, String role, boolean active) { this.hostId=hostId; this.username=username; this.displayName=displayName; this.passwordHash=passwordHash; this.role=role; this.active=active; }
    public String getHostId(){return hostId;}
    public boolean isSuperadmin(){return hostId==null;}
    public String getUsername(){return username;} public String getDisplayName(){return displayName;} public String getRole(){return role;} public boolean isActive(){return active;}
    public String passwordHash(){return passwordHash;}
    public void setPasswordHash(String passwordHash){this.passwordHash=passwordHash;}
    public void setActive(boolean active){this.active=active;}
    public String totpSecret(){return totpSecret;}
    public boolean isTotpEnabled(){return totpEnabled;}
    public void setTotpSecret(String totpSecret){this.totpSecret=totpSecret;}
    public void setTotpEnabled(boolean totpEnabled){this.totpEnabled=totpEnabled;}
}
