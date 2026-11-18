# EVILS EBICS Server

[Deutsch](README.de.md) · [Changelog](CHANGELOG.md) · [Roadmap](docs/ROADMAP.md) · [Security policy](SECURITY.md) · [Contributing](CONTRIBUTING.md)

An EBICS bank simulator with a web admin console, a persistent booking ledger and test payment
traffic. It is meant for developing and testing banking clients and treasury software against a
realistic EBICS counterpart.

> **This is a test simulator.** It is not connected to any real payment system, it is not a
> production-grade bank server, and it makes no claim of EBICS conformity. Never use it with real
> bank data or real money.

Java 21 · Spring Boot 4.1 · PostgreSQL · Docker · licensed under [GPL-3.0](LICENSE)

## What it does

**EBICS protocol**
- EBICS 3.0 (`H005`) and EBICS 2.5 (`H004`) on one endpoint (`/ebicsweb`), with HEV version discovery.
- Subscriber management with INI/HIA key initialisation, manual release, reset and deletion; bank key
  management and encrypted HPB.
- `H005`: BTD downloads, BTU uploads, HTD (accounts and permissions), HAC protocol, VEU
  (HVU/HVZ/HVD/HVT/HVE).
- `H004` (German EBICS 2.5 profile): downloads `STA` (MT940), `VMK` (MT942 intraday), `C52`/`C53`/`C54`
  (camt), `Z52`/`Z53`/`Z54` (camt as ZIP, one file per account), `Z01` (pain.002 status report) and the
  upload `CCT` (SEPA credit transfer). Not implemented and answered with `EBICS_UNSUPPORTED_ORDER_TYPE`:
  `CDD`, `CDB`, `CCU`.
- Per-subscriber permissions: upload, download and a separate permission for trade-finance messages;
  signature classes E, A, B and T per user, optionally overridden per account.

**Banking content**
- Persistent accounts and bookings; statements are produced from the ledger in the account currency.
  Formats: camt.053, camt.052, camt.054, pain.002, MT940, MT942.
- SEPA credit transfers by EBICS upload (pain.001.001.03 and .09, single and batch files, future-dated
  orders) with duplicate detection, internal credit entries, `ACSC`/`ACSP`/`PDNG`/`PART` status reports
  and signed receipts.
- Test data: generators for bookings, a daily test statement job, bank-fee test data (monthly closing,
  foreign-payment fees) and ready-made test scenarios.
- Documentary-credit demo messages (MT700/MT707/MT710/MT760), retrievable through EBICS.

**Administration**
- Web console with session login, optional TOTP two-factor login for the super admin, protocol log
  viewer and payment event history.
- Multi-tenancy: every bank (`hostId`) is an isolated tenant with its own customers, subscribers,
  accounts and admin users; public self-service sandbox signup with approval by the super admin.
- Operations: Actuator health endpoint, Docker health check, encrypted backup and restore-check scripts,
  and an optional transactional event outbox (RabbitMQ publisher, disabled by default).

The scope is an implemented subset of EBICS, not a complete implementation.

## Quick start

Requirements: JDK 21, Maven 3.9+ and Docker Compose.

```bash
mvn package
docker compose up --build -d
```

- Console: <http://localhost:8080/>
- Login: <http://localhost:8080/login> – user `admin`, password `changeit` (development defaults)
- EBICS endpoint: <http://localhost:8080/ebicsweb> – host ID `EVILSBANK`

**The development compose file is for local use only.** It publishes the database port 5432 and the app
port 8080, uses fixed development credentials, and does not pass the crypto master secret to the app.

To run the Java process on your machine instead, start only the database and set your own secrets:

```bash
docker compose up -d postgres
export EVILS_CRYPTO_MASTER_SECRET='<long random secret, keep it permanently>'
export EVILS_ADMIN_BOOTSTRAP_USERNAME='admin'
export EVILS_ADMIN_BOOTSTRAP_PASSWORD='<your own password>'
mvn spring-boot:run
```

Bootstrap credentials only create the first administrator; they do not change the password of an
existing user.

### Configuration

| Variable | Meaning / default |
| --- | --- |
| `EVILS_DB_URL` | `jdbc:postgresql://localhost:5432/evils` |
| `EVILS_DB_USERNAME` / `EVILS_DB_PASSWORD` | Database user and password, locally `evils` |
| `EVILS_ADMIN_BOOTSTRAP_USERNAME` / `_PASSWORD` | First administrator, locally `admin` / `changeit` |
| `EVILS_ADMIN_SESSION_TIMEOUT` | Console session, `8h` without activity; a server restart requires a new login |
| `EVILS_CRYPTO_MASTER_SECRET` | Protects the stored private bank keys. Keep it permanently and separate from Git |
| `EVILS_DAILY_STATEMENTS_ENABLED` / `_ACCOUNTS` / `_CRON` / `_ZONE` | Daily test booking and statement job (on, `0 0 2 * * *`, `Europe/Berlin`) |
| `evils.fees.monthly.enabled` / `.cron` / `.zone` / `.azv-per-month` | Monthly fee closing; **off by default** because it books on its own (`0 30 3 1 * *`) |
| `EVILS_EVENTS_RABBIT_ENABLED` | Optional RabbitMQ publisher for payment events, off by default |
| `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `SMTP_AUTH`, `SMTP_STARTTLS` | Optional mail for signup notifications; without a server mails are only logged |
| `MAIL_ABSENDER`, `EVILS_ADMIN_NOTIFICATION_EMAIL` | Sender and optional notification address for new signup requests |

The host ID is set in `src/main/resources/application.yml` (`EVILSBANK`). The database schema is
currently maintained by Hibernate `ddl-auto: update`; **take a database backup before every update.**
Versioned migrations are on the roadmap.

## Running on a server behind a reverse proxy

`docker-compose.prod.yml` is a **standalone** configuration, not an addition to the development file.
Do not combine the two: Compose could take over the published development ports.

1. Create an uncommitted `.env` with your own values:

   ```dotenv
   EVILS_DB_PASSWORD=<own database password>
   EVILS_CRYPTO_MASTER_SECRET=<permanent random secret>
   EVILS_ADMIN_BOOTSTRAP_USERNAME=admin
   EVILS_ADMIN_BOOTSTRAP_PASSWORD=<own admin password>
   ```

2. Provide the external Docker network named in the file (or adapt the name to your setup):

   ```bash
   docker network create proxy
   ```

3. Build and start with the server configuration only:

   ```bash
   mvn clean package
   docker compose -f docker-compose.prod.yml up --build -d
   docker compose -f docker-compose.prod.yml ps
   ```

The app listens on the host only at `127.0.0.1:8081`; PostgreSQL has no published port. TLS, domain and
reverse proxy are yours to set up. The application honours forwarded headers so that login redirects
keep the external HTTPS address.

Include the PostgreSQL volume and the crypto master secret in your backup concept, and do not replace the
secret casually: already protected keys must remain decryptable. `docker compose down -v` deletes the
database and is not a normal update step. See [Backup and restore](docs/BACKUP.md).

> Always build with `mvn clean package`. The Dockerfile copies `target/evils-ebics-server-*.jar`, and
> a stale jar or old class files from earlier builds can end up in the image.

## Connecting an EBICS client

1. Log in as administrator, check the crypto configuration and create the bank keys if needed.
2. Create subscriber, partner ID, user ID, account assignment and payment permissions to match your client.
3. In the client use the server URL `/ebicsweb`, host ID `EVILSBANK` and the subscriber data.
4. Send INI and HIA, check the keys and fingerprints in the console and release them.
5. Fetch the bank keys with HPB, then update accounts and permissions (HTD).
6. Generate test bookings and fetch statements for a period that contains them.

A subscriber reset requires a new initialisation. When something fails, read the server and the client
logs together; a generic return code alone rarely names the cause.

## Ledger and statements

An account has a configured opening balance. The opening balance of a requested statement is that plus
all bookings before the period; the closing balance adds the bookings within it. Statements are rendered
from the stored bookings and are not immutable archive files, so deleting bookings also changes
statements generated later. Accounts that have payment orders are protected against plain deletion.

| Format | Order types | Notes |
| --- | --- | --- |
| camt.053 | BTD, `C53`, `Z53` | Statement from the ledger with currency and opening/closing balance |
| camt.052 | BTD, `C52`, `Z52` | Intraday report, derived from the camt.053 structure |
| camt.054 | BTD, `C54`, `Z54` | Booking notification, simplified |
| MT940 | `STA` | German dialect, structured `:86:` field |
| MT942 | `VMK` | Intraday report of the current day by default |
| pain.002 | BTD, `Z01` | Payment status report, experimental (`pain.002.001.03`) |

A successful EBICS transfer is no proof that every client processes all report contents; camt.052,
camt.054 and pain.002 need further schema and interoperability tests.

## Payments

Manual console orders and EBICS uploads are stored as payment orders. Approval needs a single signature
class E or the combination A+B; T (technical) never approves a payment.

| Status | Meaning |
| --- | --- |
| `PENDING_SIGNATURES` | Required signatures are missing |
| `SCHEDULED` | Approved, with a future execution date |
| `APPROVED` | Approved and booked |

Due scheduled orders are checked every 30 seconds and booked one by one in separate transactions. The
EBICS upload accepts EUR in pain.001.001.03 and .09, single and batch files, with several payment
information blocks and future execution dates, up to 1000 payments and 128 KiB of order data.
Multi-segment uploads are not implemented.

An upload is validated completely and processed atomically: one invalid payment means nothing from the
file is booked. MsgId, original message type, PmtInfId, optional InstrId and EndToEndId are preserved,
and the EBICS order number is assigned permanently and referenced in HAC. Repeated uploads are detected
by partner, message ID and payload. For an internal recipient account a credit entry is created in
addition to the debit. For EUR/USD the simulator uses a **fixed test rate of 1 EUR = 1.10 USD**; other
conversions are not implemented. External recipients receive no real payment.

pain.002 reports `ACSC` for booked, `ACSP` for approved scheduled and `PDNG` for not fully approved
payments, and `PART` for mixed groups. One XML document is produced per original file; several documents
are delivered as a ZIP. A status download counts as received only after a validly signed positive EBICS
receipt. HAC delivers `FILE_UPLOAD`, optionally `VEU_FORWARDING`, and `ORDER_HAC_FINAL_POS` once fully
approved; it does not replace the payment status.

### Distributed signatures (VEU)

A new, still unsigned single EUR order from the console can be made available for VEU: choose a submitter
in the payment details and click the VEU button. That grants no signature. In the client, fetch the VEU
orders, check the details and sign yourself. Implemented: HVU/HVZ (overview), HVD (status and hash), HVT
(transaction details or the unchanged pain.001 file) and HVE (A006 signature). The original file is frozen
when it is made available; the server checks X002, subscriber and partner, order reference, hash and A006.
Only E or A+B approve, repeated valid HVE requests do not book twice, technical subscribers cannot sign.
HVS cancellation and multi-segment VEU files are not implemented (HVS is rejected, not acknowledged).
VEU is `H005` only.

### Test scenarios and payment details

The payment detail view shows persisted events with timestamps: creation, approvals, scheduling, debit,
internal credit, and the provision and receipt of reports. Administrators can create one-off test
scenarios per partner (EUR/USD, internal EUR and scheduled payments); they add new accounts and open
orders and never change existing accounts or keys.

## Console and interfaces

Admin interfaces need a logged-in session; HTTP Basic is not offered. Bank key and admin user management
are limited to the administrator role.

| Path | Purpose |
| --- | --- |
| `/` | Public landing page and sandbox signup |
| `/admin.html`, `/login` | Admin console and login |
| `/ebicsweb` | EBICS requests (POST) |
| `/admin/api/participants`, `/accounts`, `/customers`, `/tenants` | Subscribers, accounts, customers, tenants |
| `/admin/api/transactions` | Ledger; `/generate` creates test bookings, `/gebuehren-testdaten` fee test data |
| `/admin/api/statements/camt052`, `/camt053`, `/camt054` | Generate reports (`iban`, `from`, `to` as `YYYY-MM-DD`) |
| `/admin/api/payment-orders` | Payment orders |
| `/admin/api/protocol-logs` | Server protocol |
| `/admin/api/crypto/status`, `/crypto/bank-keys` | Crypto status and bank key creation |
| `/actuator/health` | Health (the only exposed Actuator endpoint) |

This table is a guide, not an API specification. The controllers in the source are authoritative.

## Tests and project structure

```bash
mvn test        # H2 profile, no database needed
mvn verify
```

The tests cover XML processing, response building, key management, persistence, uploads, report
generators, security configuration and tenant isolation, plus an end-to-end run with encrypted upload,
debit and credit, camt retrieval, HAC, pain.002 and signed receipt. Official `H004`/`H005` response
schemas and ISO pain.002/camt.053 schemas are bundled for validation. A green run is not an EBICS
certification; tests with real banking clients (for example Banking4 and S-Firm) remain necessary.

- `src/main/java/de/ostms/evils/ebics/` – server, protocol handling, services and generators
- `src/main/resources/` – configuration and console resources
- `src/test/` – tests and test configuration
- `docker-compose.yml` – local development; `docker-compose.prod.yml` – separate server configuration
- `scripts/` – encrypted backup and restore check
- `examples/rabbitmq-consumer/` – separate local reference consumer for the event outbox
- `docs/` – [roadmap](docs/ROADMAP.md), [backup](docs/BACKUP.md), [events](docs/EVENTS.md)

## Known limits

- Incoming payment files are checked structurally and by business rules but not yet fully against their
  XSD. Full EBICS conformity is not claimed.
- HPD/HKD are simplified. `H004` has no VEU, and HTD/HPD/HKD still answer in `H005` shape there.
- Batch processing is limited to SEPA credit transfers in EUR. No direct debit upload yet.
- The authentication check does not yet replace a full check of every separate order signature in all
  upload variants.
- Versioned database migrations are missing (`ddl-auto: update`).

Open work is tracked in the [roadmap](docs/ROADMAP.md) and in the GitHub issues.

## Contributing and security

Contributions are welcome, see [CONTRIBUTING.md](CONTRIBUTING.md). Please report security problems
privately as described in [SECURITY.md](SECURITY.md). EVILS is licensed under the
[GNU General Public License v3.0](LICENSE).
