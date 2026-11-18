# Changelog

## 0.24.3 — 2026-10-08
- Make the test suite pass on JDK 21 and on GitHub Actions, where it ran for the first time (0.24.2 enabled the workflow): `SchemaAssertions` no longer fails on JDK 21 because of the external `XMLSchema.dtd` reference in the official `xmldsig-core-schema.xsd` (the reference is dropped while loading, the bundled files stay untouched), and `LedgerPersistenceTest` measures its starting balance instead of assuming a fixed one, since the demo account is shared with other tests whose order differs per machine. No change to the server.

## 0.24.2 — 2026-10-08
- Documentation for the public release: the README is rewritten for the current state (0.24.1; EBICS 3.0 and 2.5, multi-tenancy, order-type and report-format tables, configuration, known limits) in English (`README.md`) with a full German version (`README.de.md`). Add `SECURITY.md` and `CONTRIBUTING.md`, move the CI workflow from `docs/` to `.github/workflows/test.yml`, and add class-level descriptions to the central classes (`EbicsController`, `EbicsResponseBuilder`, `EbicsRequestParser`, `TransactionService`, `AdminUserService`, `PaymentOrderService`, ...). No behavior change.

## 0.24.1 — 2026-10-08
- Rename the Java packages from `de.evils` to `de.ostms.evils` and the Maven `groupId` accordingly (a package name should follow a domain the project owner holds: `ostms.de`). No behavior change. Older entries below keep the former names. `AGENTS.md` now describes the current deployment procedure without server-specific names and paths.

## 0.24.0 — 2026-10-08
- Add the camt ZIP downloads and the intraday report of the German EBICS 2.5 (H004) profile (GitHub #42, part 1): `Z52`, `Z53` and `Z54` deliver the camt.052/053/054 files as a ZIP container with one XML per account (`camt053_<IBAN>_<from>_<to>.xml`), and `VMK` delivers an MT942 interim transaction report (new `Mt942Generator`: `:34F:` floor limit, `:13D:` creation time, `:61:`/`:86:` bookings of the requested period - the current day by default - and the `:90D:`/`:90C:` entry counts and sums). All four answer on the download permission of the participant like STA/C5x. Still `EBICS_UNSUPPORTED_ORDER_TYPE` (091005): `CDD`, `CDB`, `CCU`.

## 0.23.1 — 2026-10-07
- Fix the signature class check on payment upload: the upload used only the user's account-wide default class, so a participant whose default was `T` (technical) was rejected with "Technical user may not submit payments" even when a per-account override (E, A or B) allowed paying from that account. The initial check now rejects only a user who may pay from no account at all; the class is then checked per debtor account (override, else default) once the pain.001 is decrypted.

## 0.23.0 — 2026-10-02
- Add a monthly scheduled closing (`FeeClosingScheduler`): on the 1st at 03:30 (Europe/Berlin) it runs the fee generator of 0.22.0 for the previous month on every account, so the ledger gets a Monatsabschluss and AZV settlements by itself like at a real bank. **Off by default** because it creates ledger bookings on its own - enable with `evils.fees.monthly.enabled=true`; `evils.fees.monthly.cron`, `.zone` and `.azv-per-month` (default 2) are configurable. Idempotent, so a missed or repeated run is harmless

## 0.22.0 — 2026-10-02
- Add bank-fee test data for the fee analysis in OS|TMS: `POST /admin/api/transactions/gebuehren-testdaten?iban=&months=6&azvProMonat=2` (admin panel, Ledger section: "Gebühren-Testdaten") creates for each past month a **Monatsabschluss** on the last business day (Kontoführungsentgelt, Buchungsposten-Entgelt above 10 free items, EBICS-Entgelt, Soll- or Habenzinsen from the running balance) and **AZV (foreign payment) settlements per payment**: the foreign-currency payment itself (USD/GBP/CHF/JPY with rate, cost sharing SHA/OUR) plus its own fee booking (0.15 % commission, min 7.50 / max 150.00, plus 5.00 foreign-payment fee) and, for cost sharing OUR, a correspondent-bank charge booking. The fees appear only in the purpose text like on real statements. Deterministic and idempotent: existing bookings are skipped, nothing is overwritten. New `TransactionService.exists`

## 0.21.0 — 2026-10-02
- Add EBICS 2.5 (H004) business transactions (GitHub #12): the server now handles the classic order types a real EBICS 2.5 client sends instead of BTD/BTU. **Downloads:** `STA` (MT940 - new `Mt940Generator`, written so OS|TMS' `Mt940Parser` reads it: structured `:86:` with GVC and `?20..?32` subfields, 65-character lines, umlauts transliterated), `C52`/`C53`/`C54` (camt.052/053/054, same generators as BTD) and `Z01` (pain.002 status report including the Receipt phase). **Upload:** `CCT` (SEPA credit transfer, pain.001) through the existing `PaymentUploadService`, which now follows the request's protocol instead of being hardwired to H005 (a per-thread protocol context drives the namespace of every lookup and of the response envelope). Responses are answered in the H004 namespace and validated against `ebics_response_H004.xsd` in the tests. The per-participant upload/download permissions (0.20.0) apply to the classic order types too. German-profile order types that are not implemented yet (`CDD`, `CDB`, `CCU`, `VMK`, `Z52`, `Z53`, `Z54`) are answered with `EBICS_UNSUPPORTED_ORDER_TYPE` (091005) instead of a misleading bare success. Fixes found on the way: `service.EbicsAuthentication` signed/verified only in the H005 namespace, the Z01/status-report receipt read its `ReceiptCode` only in H005, and the report service answered receipts/no-data in H005 even for H004 requests. Not covered: HTD/HPD/HKD still return the H005 payload shape to H004 clients, VEU (HVU/HVZ...) stays H005-only

## 0.20.0 — 2026-10-02
- Add per-participant EBICS permissions (GitHub #34, #30): every participant now has an **upload** (BTU/FUL payment submission), a **download** (BTD statements/reports) and a separate **MT7xx** (BTD with `ServiceName=TRC`, the trade-finance documents of #30) permission, all defaulting to allowed so existing participants behave exactly as before (`ddl-auto: update` adds the three `boolean default true` columns). `/ebicsweb` enforces them with `EBICS_AUTHORISATION_ORDERTYPE_FAILED` (090003) and a protocol-log entry; an MT7xx pull needs both the download and the MT7xx permission, so a user can have statements without being allowed to see letters of credit. The HTD response only announces the orders a participant may actually perform. Admin panel: new "Rechte" column in the participant table with one toggle per permission; API `PATCH /admin/api/participants/{userId}/permissions?upload=&download=&lcDownload=` (each parameter optional, tenant-scoped like the other participant endpoints)

## 0.19.0 — 2026-10-02
- Remove the FinTS/RDH mock (`de.evils.fints`: `FintsParticipant`/`FintsParticipantKey`, their service, repositories, admin controller and the TCP port-3000 listener configuration) and everything that existed only for it: the `evils.fints.port` setting, the port-3000 mappings in `Dockerfile`/`docker-compose.yml`/`docker-compose.prod.yml`, and the explicit `@ComponentScan`/`@EntityScan`/`@EnableJpaRepositories` for the sibling package in `EbicsServerApplication` (the default scan of `de.evils.ebics` is enough again). evils is an EBICS-only mock server from now on; OS|TMS' RDH client is tested against a real bank instead. **Existing databases keep the now-unused `fints_participants`/`fints_participant_keys` tables** (`ddl-auto: update` never drops anything) - they can be dropped manually

## 0.18.0 — 2026-09-30
- Add a "Details" button to each EBICS protocol log entry (admin panel, "Protokolle" view): the log table uses `white-space:nowrap`, so a long outcome/rejection-reason text was only readable by scrolling the table sideways, split apart from the entry's other fields. The button opens a dialog with all fields (timestamp, Host-ID, protocol version, PartnerID, UserID, order type, reference, outcome, full detail text) gathered in one readable place - same native `<dialog>`/`showModal()` pattern already used for payment details

## 0.17.0 — 2026-09-30
- Add MT700/MT707/MT710/MT760 retrieval via real EBICS BTD downloads (GitHub #30) - a generated documentary-credit message no longer only lives as a one-off admin-panel API response: every generated document is now persisted (`LcDocument`/`LcDocumentRepository`) against the tenant's customer, and an EBICS client can pull the most recently generated one for its PartnerID via a BTD request with `ServiceName=TRC`/`MsgName=MT700|MT707|MT710|MT760`, same `downloadInitialization` path as camt.053/pain.002. **Not a real EBICS or ISO 20022 standard** - MT7xx trade-finance messages travel over SWIFT FIN/SWIFTNet Trade Services in the real world, never EBICS - this is a deliberate, clearly-documented mock-server extension so tools like corporate-lc-manager can exercise a pull-based retrieval flow end-to-end. Also adds `Mt710Generator` (Advice of a Third/Confirming Bank's Documentary Credit, new `:52A:` issuing-bank field) and `GET /admin/api/swift/mt710`; all four admin generator endpoints now accept an optional `partnerId` to control who can retrieve the document (defaults to the tenant's first active customer)

## 0.16.0 — 2026-09-30
- Add MT700/MT707/MT760 demo message generation to the admin panel ("Konten" view, new "SWIFT-Nachrichten (Akkreditiv-Demo)" section) - a realistic documentary credit issuance, amendment, or bank guarantee message with one click, in the same slim field dialect corporate-lc-manager's own `Mt700Parser`/`Mt707Parser` expect, so a generated message can be pasted there directly. Applicant/beneficiary are pulled from the tenant's existing customers when available. New `de.evils.ebics.swift` package (`Mt700Generator`/`Mt707Generator`/`Mt760Generator`), `GET /admin/api/swift/mt700`, `/mt707` (optional `lcReferenz` to tie an amendment to a specific LC), `/mt760`

## 0.15.0 — 2026-09-30
- Add Spring Boot Actuator (`/actuator/health` only) and a Docker `HEALTHCHECK` for the `app` service, so `docker compose ps`/an orchestrator can show real readiness instead of none at all (GitHub #214). Mail and RabbitMQ health indicators are disabled - both are optional (no SMTP server or broker running in this deployment) and would otherwise report the whole container unhealthy, or in tests, fail to even build the application context (`mailHealthContributor`/`rabbitHealthContributor` need a `beans` map that doesn't exist without them)

## 0.14.0 — 2026-09-30
- Fix: add `restart: unless-stopped` to both services in `docker-compose.prod.yml` - after a host reboot the containers weren't coming back up automatically (no restart policy was ever declared here, unlike the sibling OS|TMS deployment which had it set, but only manually on the server, never committed either - see that project's matching fix)

## 0.13.0 — 2026-09-27
- Add a "Kunde anlegen" form (PartnerID + Name) to the Konten admin view: the backend already supported `POST /admin/api/customers`, but the admin UI only ever fetched customers to populate dropdowns - there was no way to create the first one without a raw API call. New customers appear immediately in a "Kunden" card list and in the existing PartnerID dropdowns (participant/account forms) after creation

## 0.12.0 — 2026-09-27
- Add an "IBAN generieren" button next to the IBAN field in the Testkonto-anlegen form: fills in a fresh, Mod-97-valid, still-unused German test IBAN instead of requiring one to be hand-typed (the previous source of a real-world "IBAN-Prüfsumme ist ungültig" payment rejection turned out to be exactly this — a manually-entered test IBAN that failed the checksum). The existing IBAN-generation logic used internally by Testszenarien was extracted into a shared `IbanGenerator` utility so both features use the same, tested implementation instead of duplicating it; new `GET /admin/api/accounts/generate-iban` endpoint backs the button

## 0.11.0 — 2026-09-26
- Add optional admin notification email (`EVILS_ADMIN_NOTIFICATION_EMAIL`) sent on every new tenant signup request, so a SUPERADMIN doesn't have to poll the "Offene Anfragen" list manually; unset by default (no behavior change for existing deployments)

## 0.10.0 — 2026-09-26
- Add email notifications for tenant signup requests: the requester now provides their email address on the signup form and gets notified when a SUPERADMIN approves or rejects the request (new `spring-boot-starter-mail`-based `MailService`, mirroring OS|TMS's own `MailService`); a mail failure never blocks the approve/reject decision itself

## 0.9.0 — 2026-09-26
- Add a public landing page at `/` (what used to be the login-gated admin dashboard, moved to `/admin.html`) explaining the service and linking to the sandbox signup form and the admin login

## 0.8.0 — 2026-09-26
- Add public, unauthenticated self-service tenant signup (`/signup.html` → `POST /api/tenant-signup`): the requester sets their own future admin username/password up front (hashed immediately, never stored in plaintext) since no email-sending capability exists; nothing is created until a SUPERADMIN approves the pending request from the admin UI's new "Offene Anfragen" section
- Add end-to-end `SecurityConfigurationTest` against the real filter chain, specifically targeting the class of bug behind the two prior production incidents in this feature (a URL matcher wrongly locking or wrongly opening an endpoint)

## 0.7.0 — 2026-09-25
- Add multi-tenancy foundation: every Bank row (hostId) is now an isolated tenant with its own Customers/Participants/Accounts and its own scoped AdminUser logins (no shared login/tenant switcher); a SUPERADMIN (the former single bootstrap admin) can create/list/delete tenants via `/admin/api/tenants`
- Scope every admin API and the EBICS protocol path's identity checks (Customer/Participant lookup) to the request's tenant; add a cross-tenant isolation regression test
- Deliberately deferred to a follow-up: statements/protocol logs/backend events and the daily-statement scheduler are not yet tenant-tagged (IBAN/UserID stay globally unique, same as before), and `de.evils.fints` (BLZ-keyed) is untouched

## 0.6.0 — 2026-09-24
- Add optional RabbitMQ publisher with broker confirmations and mandatory routing; external dispatch remains disabled by default
- Add isolated local RabbitMQ reference consumer with persistent deduplication, bounded retries, confirmed dead-letter delivery and health/metrics endpoints
- Verify local broker outage/reconnect, consumer restart and Java payment-service → transactional outbox → RabbitMQ → consumer flow; reference consumer does not execute payments
- Confirm EBICS interoperability: S-Firm account retrieval and transfer completed successfully in the test environment (user validation)
- Add admin outbox inspection, manual DEAD replay with audit records and retry reset
- Add configurable, account-scoped daily test statements with transactional run protection
- Filter ledger by selected account, extend admin inactivity timeout to eight hours and include order identifiers in protocol metadata
- Introduce environment-backed CredentialProvider foundation; external secret stores and rotation remain roadmap work
- Extend roadmap with tenant isolation, four-eyes administration and self-service portal
- Prepare broker-neutral EventPublisher with transactional payment-event outbox, retry/backoff, dead-event retention and admin counters
- Add explicit publication of unsigned EUR GUI drafts to H005 VEU; HVU/HVZ/HVD/HVT downloads and cryptographically verified A006 HVE approval, with duplicate protection and E/A+B regression tests
- Fix signed empty HAC/pain.002 responses returning a misleading participant error; add no-data regression coverage
- Schema-validated HAC and pain.002 reports with original payment references and acknowledged download tracking
- Atomic batch uploads, duplicate detection, scheduled bookings, and payment event history
- Payment-detail dialog with live refresh and additive admin test scenarios
- Encrypted backup and isolated restore-check scripts with recovery documentation
- Official-schema regression tests for upload responses, payment reports and CAMT.053; CI workflow template (activation requires GitHub workflow permission)
- Encrypted-at-rest RSA-3072 bank key foundation with admin-only key-set generation

## 0.5.0
- PostgreSQL persistence through Spring Data JPA and `docker-compose.yml`
- H2 `test` profile for automated tests
- Persistent bank, customer, participant, account, and ledger-entry data
- Account opening-balance date and ledger-derived historical opening/closing balances
- `POST /admin/api/transactions` to book credit and debit entries
- CAMT.053 and CAMT.054 now read current data from the persistent ledger
- Local admin interface with ledger, CAMT, and persistent EBICS participant management
- Persistent per-user EBICS payment signature classes: E alone; A with B; B with A; T technical
- Payment-order workflow: create, sign, approve, and ledger booking after E or A+B
- Password-protected local admin access with persistent BCrypt-hashed admin accounts
- HAC simulator: records incoming EBICS Customer Acknowledgements and lists them in admin
- Persistent development-only INI/HIA/HPB workflow simulator and initialization audit trail
- H005 simulator responses for HPD bank information and HKD participant/key information
- Persistent metadata-only EBICS protocol log with protected admin view

## 0.4.0
- Transaction and statement engine
- CAMT.053 generator
- CAMT.054 generator
- Admin endpoints for transactions and statements

## 0.3.0
- Bank, customer, participant and account domain
- HostID / PartnerID / UserID checks
- Admin API

## 0.2.0
- XSD validation infrastructure
- EBICS error model

## 0.1.0
- Spring Boot / Java 21 foundation
- `/ebicsweb` endpoint
- Secure XML parsing
