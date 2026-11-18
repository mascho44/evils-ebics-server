# Security policy

EVILS is an EBICS **test simulator**. It must not be connected to real banking data, real keys or real
money, and it is not a production-grade bank server. It does handle key material and logins, so security
reports are still welcome.

## Reporting a vulnerability

Please report problems **privately** and do not open a public issue:

1. Use GitHub's private reporting: on the repository page open **Security → Report a vulnerability**.
2. Describe the problem, the affected version (see [CHANGELOG.md](CHANGELOG.md)), and how to reproduce it.
   Do not include real credentials or real bank data.

You will get an answer as soon as possible. Please allow time for a fix before you disclose the issue
publicly.

## Scope

Of particular interest: authentication and session handling of the admin console, tenant isolation,
storage of private bank keys (`EVILS_CRYPTO_MASTER_SECRET`), signature and hash checks in the EBICS
paths (X002, A006, VEU), and anything that lets one tenant read or change another tenant's data.

Out of scope: the missing production readiness listed in the README ("Known limits"), the fixed
development credentials of `docker-compose.yml`, and denial of service against a deliberately small
test server.

## Hardening checklist for a public instance

- Set your own `EVILS_ADMIN_BOOTSTRAP_PASSWORD`, `EVILS_DB_PASSWORD` and `EVILS_CRYPTO_MASTER_SECRET`;
  never run with the defaults (`changeit`, `evils`).
- Use `docker-compose.prod.yml` behind a TLS reverse proxy; do not publish the database port.
- Keep `.env`, backups and the backup passphrase out of version control.
- Enable the optional TOTP two-factor login for the super admin.

---

*Deutsch:* Sicherheitsprobleme bitte **vertraulich** über GitHub melden (**Security → Report a
vulnerability**), nicht als öffentliches Issue. Keine echten Zugangsdaten oder Bankdaten beilegen.
