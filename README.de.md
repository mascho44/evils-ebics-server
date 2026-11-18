# EVILS EBICS Server

[English](README.md) · [Changelog](CHANGELOG.md) · [Roadmap](docs/ROADMAP.md) · [Sicherheitsrichtlinie](SECURITY.md) · [Mitwirken](CONTRIBUTING.md)

Ein EBICS-Banksimulator mit Webverwaltung, persistentem Buchungsjournal und Test-Zahlungsverkehr. Er
dient der Entwicklung und dem Test von Banking-Clients und Treasury-Software gegen ein realistisches
EBICS-Gegenüber.

> **Dies ist ein Testsimulator.** Er ist an keinen echten Zahlungsverkehr angebunden, kein
> produktionsreifer Bankserver und erhebt keinen Anspruch auf EBICS-Konformität. Niemals mit echten
> Bankdaten oder echtem Geld verwenden.

Java 21 · Spring Boot 4.1 · PostgreSQL · Docker · Lizenz [GPL-3.0](LICENSE)

## Funktionsumfang

**EBICS-Protokoll**
- EBICS 3.0 (`H005`) und EBICS 2.5 (`H004`) an einem Endpunkt (`/ebicsweb`), mit HEV-Versionsabfrage.
- Teilnehmerverwaltung mit INI/HIA-Schlüsselinitialisierung, manueller Freigabe, Zurücksetzen und
  Löschen; Bankschlüsselverwaltung und verschlüsseltes HPB.
- `H005`: BTD-Downloads, BTU-Uploads, HTD (Konten und Berechtigungen), HAC-Protokoll, VEU
  (HVU/HVZ/HVD/HVT/HVE).
- `H004` (deutsches EBICS-2.5-Profil): Downloads `STA` (MT940), `VMK` (MT942, untertägig),
  `C52`/`C53`/`C54` (camt), `Z52`/`Z53`/`Z54` (camt als ZIP, je Konto eine Datei), `Z01` (pain.002-
  Statusbericht) und der Upload `CCT` (SEPA-Überweisung). Nicht umgesetzt und mit
  `EBICS_UNSUPPORTED_ORDER_TYPE` beantwortet: `CDD`, `CDB`, `CCU`.
- Berechtigungen je Teilnehmer: Upload, Download und eine eigene Berechtigung für
  Handelsfinanzierungsnachrichten; Signaturklassen E, A, B und T je Benutzer, optional je Konto
  überschrieben.

**Bankinhalte**
- Persistente Konten und Buchungen; Auszüge entstehen aus dem Journal in der Kontowährung. Formate:
  camt.053, camt.052, camt.054, pain.002, MT940, MT942.
- SEPA-Überweisungen per EBICS-Upload (pain.001.001.03 und .09, Einzel- und Sammeldateien,
  Terminaufträge) mit Dublettenerkennung, internen Gegenbuchungen, Statusberichten
  `ACSC`/`ACSP`/`PDNG`/`PART` und signierten Empfangsbestätigungen.
- Testdaten: Generatoren für Buchungen, ein täglicher Test-Auszugsjob, Bankgebühren-Testdaten
  (Monatsabschluss, Auslandszahlungsentgelte) und fertige Testszenarien.
- Akkreditiv-Demonachrichten (MT700/MT707/MT710/MT760), per EBICS abrufbar.

**Verwaltung**
- Webverwaltung mit Sitzungsanmeldung, optionaler TOTP-Zwei-Faktor-Anmeldung für den Super-Admin,
  Protokollansicht und Ereignisverlauf je Zahlung.
- Mandantenfähigkeit: Jede Bank (`hostId`) ist ein isolierter Mandant mit eigenen Kunden, Teilnehmern,
  Konten und Admin-Benutzern; öffentliche Sandbox-Selbstregistrierung mit Freigabe durch den Super-Admin.
- Betrieb: Actuator-Health-Endpunkt, Docker-Healthcheck, verschlüsselte Backup- und Restore-Check-
  Skripte und eine optionale transaktionale Event-Outbox (RabbitMQ-Publisher, standardmäßig aus).

Der Umfang ist ein implementierter EBICS-Teilbereich, keine vollständige Umsetzung.

## Schnellstart

Voraussetzungen: JDK 21, Maven 3.9+ und Docker Compose.

```bash
mvn package
docker compose up --build -d
```

- Verwaltung: <http://localhost:8080/>
- Anmeldung: <http://localhost:8080/login> – Benutzer `admin`, Passwort `changeit` (Entwicklungsvorgaben)
- EBICS-Endpunkt: <http://localhost:8080/ebicsweb> – Host-ID `EVILSBANK`

**Die Entwicklungs-Compose-Datei ist nur für lokale Nutzung.** Sie veröffentlicht den Datenbank-Port 5432
und den App-Port 8080, setzt feste Entwicklungszugänge und reicht den Crypto-Master-Secret nicht an die
App weiter.

Um den Java-Prozess stattdessen direkt auf dem eigenen Rechner zu starten, nur die Datenbank hochfahren
und eigene Geheimnisse setzen:

```bash
docker compose up -d postgres
export EVILS_CRYPTO_MASTER_SECRET='<langer zufälliger Wert, dauerhaft aufbewahren>'
export EVILS_ADMIN_BOOTSTRAP_USERNAME='admin'
export EVILS_ADMIN_BOOTSTRAP_PASSWORD='<eigenes Passwort>'
mvn spring-boot:run
```

Die Bootstrap-Zugangsdaten legen nur den ersten Administrator an; sie ändern kein Passwort eines
bestehenden Benutzers.

### Konfiguration

| Variable | Bedeutung / Vorgabe |
| --- | --- |
| `EVILS_DB_URL` | `jdbc:postgresql://localhost:5432/evils` |
| `EVILS_DB_USERNAME` / `EVILS_DB_PASSWORD` | Datenbankbenutzer und -passwort, lokal `evils` |
| `EVILS_ADMIN_BOOTSTRAP_USERNAME` / `_PASSWORD` | Erster Administrator, lokal `admin` / `changeit` |
| `EVILS_ADMIN_SESSION_TIMEOUT` | Sitzung der Verwaltung, `8h` ohne Aktivität; ein Serverneustart erfordert neue Anmeldung |
| `EVILS_CRYPTO_MASTER_SECRET` | Schützt die gespeicherten privaten Bankschlüssel. Dauerhaft und getrennt von Git aufbewahren |
| `EVILS_DAILY_STATEMENTS_ENABLED` / `_ACCOUNTS` / `_CRON` / `_ZONE` | Täglicher Testbuchungs- und Auszugsjob (an, `0 0 2 * * *`, `Europe/Berlin`) |
| `evils.fees.monthly.enabled` / `.cron` / `.zone` / `.azv-per-month` | Monatlicher Gebührenabschluss; **standardmäßig aus**, weil er selbständig bucht (`0 30 3 1 * *`) |
| `EVILS_EVENTS_RABBIT_ENABLED` | Optionaler RabbitMQ-Publisher für Zahlungsereignisse, standardmäßig aus |
| `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `SMTP_AUTH`, `SMTP_STARTTLS` | Optionale Mail für Registrierungsbenachrichtigungen; ohne Server werden Mails nur protokolliert |
| `MAIL_ABSENDER`, `EVILS_ADMIN_NOTIFICATION_EMAIL` | Absender und optionale Benachrichtigungsadresse für neue Registrierungsanträge |

Die Host-ID steht in `src/main/resources/application.yml` (`EVILSBANK`). Das Datenbankschema wird derzeit
mit Hibernate `ddl-auto: update` angepasst; **vor jedem Update eine Datenbanksicherung erstellen.**
Versionierte Migrationen stehen auf der Roadmap.

## Serverbetrieb hinter einem Reverse Proxy

`docker-compose.prod.yml` ist eine **eigenständige** Konfiguration, keine Ergänzung zur Entwicklungsdatei.
Beide Dateien nicht kombinieren: Compose kann sonst die veröffentlichten Entwicklungsports übernehmen.

1. Eine nicht eingecheckte `.env` mit eigenen Werten anlegen:

   ```dotenv
   EVILS_DB_PASSWORD=<eigenes Datenbankpasswort>
   EVILS_CRYPTO_MASTER_SECRET=<dauerhafter zufälliger Wert>
   EVILS_ADMIN_BOOTSTRAP_USERNAME=admin
   EVILS_ADMIN_BOOTSTRAP_PASSWORD=<eigenes Admin-Passwort>
   ```

2. Das in der Datei genannte externe Docker-Netzwerk bereitstellen (oder den Namen anpassen):

   ```bash
   docker network create proxy
   ```

3. Bauen und ausschließlich mit der Server-Konfiguration starten:

   ```bash
   mvn clean package
   docker compose -f docker-compose.prod.yml up --build -d
   docker compose -f docker-compose.prod.yml ps
   ```

Die App ist am Host nur über `127.0.0.1:8081` erreichbar; PostgreSQL hat keinen veröffentlichten Port.
TLS, Domain und Reverse Proxy richtest du selbst ein. Die Anwendung wertet Forwarded-Header aus, damit
Anmeldeweiterleitungen die externe HTTPS-Adresse behalten.

Das PostgreSQL-Volume und den Crypto-Master-Secret gemeinsam in die Sicherung aufnehmen und den Secret
nicht beliebig austauschen: Bereits geschützte Schlüssel müssen weiterhin entschlüsselbar bleiben.
`docker compose down -v` löscht die Datenbank und ist kein normaler Update-Schritt. Siehe
[Sicherung und Wiederherstellung](docs/BACKUP.md).

> Immer mit `mvn clean package` bauen. Das Dockerfile kopiert `target/evils-ebics-server-*.jar`, und ein
> altes JAR oder alte Klassen früherer Builds können sonst im Image landen.

## Einen EBICS-Client einrichten

1. Als Administrator anmelden, die Crypto-Konfiguration prüfen und gegebenenfalls Bankschlüssel erzeugen.
2. Teilnehmer, Partner-ID, Benutzer-ID, Kontenzuordnung und Zahlungsberechtigungen passend zum Client anlegen.
3. Im Client die Server-URL `/ebicsweb`, die Host-ID `EVILSBANK` und die Teilnehmerdaten verwenden.
4. INI und HIA senden, Schlüssel und Fingerprints in der Verwaltung prüfen und freigeben.
5. Bankschlüssel per HPB abholen, danach Konten und Berechtigungen aktualisieren (HTD).
6. Testbuchungen erzeugen und Auszüge für einen Zeitraum abrufen, der diese Buchungen einschließt.

Ein Teilnehmer-Reset erfordert eine erneute Initialisierung. Bei Fehlern Server- und Client-Protokolle
gemeinsam lesen; ein generischer Rückgabecode allein benennt selten die Ursache.

## Buchungsjournal und Auszüge

Ein Konto hat einen konfigurierten Anfangssaldo. Der Anfangssaldo eines angefragten Auszugs ist dieser
plus alle Buchungen vor dem Zeitraum; der Endsaldo berücksichtigt zusätzlich die Buchungen im Zeitraum.
Auszüge werden aus den gespeicherten Buchungen erzeugt und sind keine unveränderlichen Archivdateien: Das
Löschen von Buchungen verändert auch später erzeugte Auszüge. Konten mit Zahlungsaufträgen sind gegen
einfaches Löschen geschützt.

| Format | Auftragsarten | Hinweis |
| --- | --- | --- |
| camt.053 | BTD, `C53`, `Z53` | Kontoauszug aus dem Journal mit Währung und Anfangs-/Endsaldo |
| camt.052 | BTD, `C52`, `Z52` | Untertägiger Bericht, aus der camt.053-Struktur abgeleitet |
| camt.054 | BTD, `C54`, `Z54` | Buchungsbenachrichtigung, vereinfacht |
| MT940 | `STA` | Deutscher Dialekt, strukturiertes Feld `:86:` |
| MT942 | `VMK` | Untertägiger Bericht, standardmäßig der aktuelle Tag |
| pain.002 | BTD, `Z01` | Zahlungsstatusbericht, experimentell (`pain.002.001.03`) |

Eine erfolgreiche EBICS-Übertragung ist kein Nachweis, dass jeder Client alle Berichtsinhalte verarbeitet;
camt.052, camt.054 und pain.002 benötigen weitere Schema- und Interoperabilitätstests.

## Zahlungsverkehr

Manuelle Aufträge aus der Verwaltung und EBICS-Uploads werden als Zahlungsaufträge gespeichert. Die
Freigabe braucht die Einzelunterschrift E oder die Kombination A+B; T (technisch) gibt nie frei.

| Status | Bedeutung |
| --- | --- |
| `PENDING_SIGNATURES` | Erforderliche Unterschriften fehlen |
| `SCHEDULED` | Freigegeben, mit zukünftigem Ausführungstag |
| `APPROVED` | Freigegeben und gebucht |

Fällige Terminaufträge werden alle 30 Sekunden geprüft und einzeln in eigenen Transaktionen gebucht. Der
EBICS-Upload unterstützt EUR in pain.001.001.03 und .09, Einzel- und Sammeldateien, mehrere Payment-
Information-Blöcke und zukünftige Ausführungstage, bis zu 1000 Zahlungen und 128 KiB Nutzdaten.
Mehrsegmentige Uploads sind nicht implementiert.

Ein Upload wird vollständig geprüft und atomar verarbeitet: Eine ungültige Zahlung bedeutet, dass nichts
aus der Datei gebucht wird. MsgId, ursprünglicher Nachrichtentyp, PmtInfId, optionale InstrId und
EndToEndId bleiben erhalten; die EBICS-Auftragsnummer wird dauerhaft vergeben und im HAC referenziert.
Wiederholte Uploads werden anhand von Partner, Message-ID und Nutzdaten erkannt. Bei einem internen
Empfängerkonto entsteht zusätzlich zur Belastung eine Gutschrift. Für EUR/USD gilt ein **fester Testkurs
von 1 EUR = 1,10 USD**; andere Umrechnungen sind nicht implementiert. Externe Empfänger erhalten keine
echte Zahlung.

pain.002 meldet `ACSC` für gebuchte, `ACSP` für freigegebene terminierte und `PDNG` für noch nicht
vollständig freigegebene Zahlungen, `PART` für gemischte Gruppen. Je ursprünglicher Datei entsteht ein XML-
Dokument; mehrere Dokumente werden als ZIP geliefert. Ein Statusdownload gilt erst nach einem gültig
signierten positiven EBICS-Receipt als empfangen. HAC liefert `FILE_UPLOAD`, gegebenenfalls
`VEU_FORWARDING` und nach vollständiger Freigabe `ORDER_HAC_FINAL_POS`; er ersetzt nicht den Zahlungsstatus.

### Verteilte Unterschriften (VEU)

Ein neuer, noch unsignierter einzelner EUR-Auftrag aus der Verwaltung kann für VEU bereitgestellt werden:
in den Zahlungsdetails einen Einreicher wählen und die VEU-Schaltfläche anklicken. Das erteilt keine
Unterschrift. Im Client die VEU-Aufträge abrufen, Details prüfen und selbst unterschreiben. Umgesetzt:
HVU/HVZ (Übersicht), HVD (Status und Hash), HVT (Transaktionsdetails oder die unveränderte pain.001-Datei)
und HVE (A006-Unterschrift). Die Originaldatei wird beim Bereitstellen eingefroren; der Server prüft X002,
Teilnehmer und Partner, Auftragsreferenz, Hash und A006. Nur E oder A+B geben frei, wiederholte gültige
HVE-Anfragen buchen nicht doppelt, technische Teilnehmer dürfen nicht signieren. HVS-Storno und
mehrsegmentige VEU-Dateien sind nicht implementiert (HVS wird abgelehnt, nicht quittiert). VEU gibt es nur
in `H005`.

### Testszenarien und Zahlungsdetails

Die Detailansicht zeigt gespeicherte Ereignisse mit Zeitstempel: Anlage, Freigaben, Terminierung,
Belastung, interne Gutschrift sowie Bereitstellung und Empfang von Berichten. Administratoren können pro
Partner einmalig Testszenarien anlegen (EUR/USD, interne EUR- und Terminzahlungen); sie legen neue Konten
und offene Aufträge an und verändern nie bestehende Konten oder Schlüssel.

## Verwaltung und Schnittstellen

Admin-Schnittstellen benötigen eine angemeldete Sitzung; HTTP Basic wird nicht angeboten. Bankschlüssel-
und Admin-Benutzerverwaltung sind auf die Administratorrolle beschränkt.

| Pfad | Zweck |
| --- | --- |
| `/` | Öffentliche Startseite und Sandbox-Registrierung |
| `/admin.html`, `/login` | Verwaltung und Anmeldung |
| `/ebicsweb` | EBICS-Anfragen (POST) |
| `/admin/api/participants`, `/accounts`, `/customers`, `/tenants` | Teilnehmer, Konten, Kunden, Mandanten |
| `/admin/api/transactions` | Journal; `/generate` erzeugt Testbuchungen, `/gebuehren-testdaten` Gebühren-Testdaten |
| `/admin/api/statements/camt052`, `/camt053`, `/camt054` | Berichte erzeugen (`iban`, `from`, `to` als `YYYY-MM-DD`) |
| `/admin/api/payment-orders` | Zahlungsaufträge |
| `/admin/api/protocol-logs` | Serverprotokoll |
| `/admin/api/crypto/status`, `/crypto/bank-keys` | Crypto-Status und Bankschlüssel erzeugen |
| `/actuator/health` | Health (einziger veröffentlichter Actuator-Endpunkt) |

Die Tabelle ist eine Orientierung, keine API-Spezifikation. Verbindlich sind die Controller im Quellcode.

## Tests und Projektstruktur

```bash
mvn test        # H2-Profil, keine Datenbank nötig
mvn verify
```

Die Tests betreffen XML-Verarbeitung, Antwortaufbau, Schlüsselverwaltung, Persistenz, Uploads,
Berichtsgeneratoren, Sicherheitskonfiguration und Mandantentrennung sowie einen Ende-zu-Ende-Lauf mit
verschlüsseltem Upload, Belastung und Gutschrift, CAMT-Abruf, HAC, pain.002 und signiertem Receipt.
Offizielle `H004`-/`H005`-Antwortschemas und ISO-pain.002-/camt.053-Schemas sind zur Validierung
eingebunden. Ein grüner Lauf ist keine EBICS-Zertifizierung; Tests mit echten Banking-Clients (zum
Beispiel Banking4 und S-Firm) bleiben nötig.

- `src/main/java/de/ostms/evils/ebics/` – Server, Protokollverarbeitung, Dienste und Generatoren
- `src/main/resources/` – Konfiguration und Ressourcen der Verwaltung
- `src/test/` – Tests und Testkonfiguration
- `docker-compose.yml` – lokale Entwicklung; `docker-compose.prod.yml` – eigenständige Server-Konfiguration
- `scripts/` – verschlüsseltes Backup und Restore-Check
- `examples/rabbitmq-consumer/` – eigener lokaler Referenz-Consumer für die Event-Outbox
- `docs/` – [Roadmap](docs/ROADMAP.md), [Backup](docs/BACKUP.md), [Events](docs/EVENTS.md)

## Bekannte Grenzen

- Eingehende Zahlungsdateien werden strukturell und fachlich geprüft, aber noch nicht vollständig gegen
  ihr XSD. Vollständige EBICS-Konformität wird nicht zugesichert.
- HPD/HKD sind vereinfacht. `H004` hat kein VEU, und HTD/HPD/HKD antworten dort noch in `H005`-Form.
- Die Sammelverarbeitung ist auf SEPA-Überweisungen in EUR begrenzt. Noch kein Lastschrift-Upload.
- Die Authentifizierungsprüfung ersetzt noch keine vollständige Prüfung aller getrennten
  Auftragssignaturen in sämtlichen Upload-Varianten.
- Versionierte Datenbankmigrationen fehlen (`ddl-auto: update`).

Offene Arbeiten stehen in der [Roadmap](docs/ROADMAP.md) und in den GitHub-Issues.

## Mitwirken und Sicherheit

Beiträge sind willkommen, siehe [CONTRIBUTING.md](CONTRIBUTING.md). Sicherheitsprobleme bitte vertraulich
melden, wie in [SECURITY.md](SECURITY.md) beschrieben. EVILS steht unter der
[GNU General Public License v3.0](LICENSE).
