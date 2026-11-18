# RabbitMQ-Referenz-Backend (nur lokal)

Separates Python-Beispiel, keine Änderungen am Serverbetrieb und keine Zahlungsabwicklung.
Verarbeitet den tatsächlichen `IntegrationEvent`-Vertrag v1 des Servers. Neue `kind`-Werte
sind erlaubt; Version, Herkunft, Datum und zusammengehörige IDs werden geprüft.
SQLite speichert nur Event-ID, Inhaltsfingerabdruck, Zahlungs-ID, Ereignisart und Verarbeitungszeit.

## Start

Docker muss laufen. In diesem Verzeichnis:

```sh
docker compose up -d --build --wait
curl --fail http://127.0.0.1:8091/health
```

Lokaler Broker: Port 5673. Management: http://127.0.0.1:15673
Testzugang: `evils-demo` / `local-test-only`. Nur Loopback-Ports, nicht für öffentliche
Bereitstellung geeignet. Eigene benannte Volumes erhalten Brokerdaten und Inbox bei Neustarts.
`docker compose down` stoppt das Beispiel ohne Datenlöschung.

## Automatisierte Tests und Broker-Smoke-Test

```sh
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
.venv/bin/python -m unittest -v
export RABBITMQ_URL='amqp://evils-demo:local-test-only@127.0.0.1:5673/%2F'
.venv/bin/python smoke.py
curl --fail http://127.0.0.1:8091/metrics
```

Nach dem Smoke-Test steigen `processed`, `duplicates` und `dead` um jeweils eins.
Die Fehlernachricht liegt in `evils.backend.reference.dead`. Es wird **keine Überweisung** angelegt.
Nach einem Consumer-Neustart bleibt der Dublettenschutz erhalten; die Laufzeitzähler starten bei null.

Wiederholbarer Integrationstest (nur gegen diesen lokalen Compose-Stack):

```sh
.venv/bin/python integration_test.py
```

Er prüft Empfang, Dubletten, Fehlerqueue, Consumer-Neustart, HTTP 503 bei Broker-Ausfall
und automatische Wiederverbindung. Dazu startet er ausschließlich diese beiden lokalen
Testdienste neu. Testereignis und Fehlernachricht bleiben zur Inspektion gespeichert.
Am 24.09.2026 mit RabbitMQ 4.1 erfolgreich ausgeführt; außerdem sieben Einzeltests bestanden.
Dies prüft den Consumer mit Testnachrichten. Für die Java-Outbox-Anbindung gibt es
zusätzlich einen explizit aktivierbaren Test (aus dem Repository-Hauptverzeichnis):

```sh
mvn -Dtest=RabbitOutboxLocalTest -Devils.localRabbitTest=true test
```

Er verwendet eine eigene flüchtige H2-Datenbank, legt über den Zahlungsservice einen
10-Euro-Entwurf an und liefert dessen `CREATED`-Ereignis über `EventOutboxDelivery`
und den echten RabbitMQ-Adapter aus. Er prüft `PENDING → DELIVERED`, den Empfang
im Consumer und die Erkennung einer erneuten Zustellung. Der Scheduler bleibt dabei
deaktiviert; es wird gezielt nur dieses Ereignis ausgeliefert. Keine Freigabe, keine
Buchung, kein Zugriff auf die entfernte Testbank. Ohne Aktivierungsparameter wird der Test übersprungen.
Die ausgegebene `LOCAL_OUTBOX_VERIFIED`-ID lässt sich in der lokalen SQLite-Inbox zuordnen.

Falls RabbitMQ beim ersten Start `Error when reading ... .erlang.cookie: eacces` meldet:
Den Besitzer der Datei im **lokalen Beispiel-Volume** prüfen. Bei falschem Besitzer kann
die folgende gezielte Korrektur helfen (keine Volumes löschen):

```sh
docker compose stop rabbitmq
docker compose run --rm --no-deps --user root --entrypoint sh rabbitmq -c 'chown rabbitmq:rabbitmq /var/lib/rabbitmq/.erlang.cookie && chmod 600 /var/lib/rabbitmq/.erlang.cookie'
docker compose up -d --wait
```

## Lokalen EBICS-Server verbinden (optional, ausdrücklich aktivieren)

Zuerst Consumer starten, damit Exchange und Queue existieren. Für einen auf dem Host laufenden
Server zusätzlich zu seiner bisherigen lokalen Konfiguration setzen:

```sh
export EVILS_EVENTS_RABBIT_ENABLED=true
export EVILS_EVENTS_DISPATCHENABLED=true
export SPRING_RABBITMQ_HOST=127.0.0.1
export SPRING_RABBITMQ_PORT=5673
export SPRING_RABBITMQ_USERNAME=evils-demo
export SPRING_RABBITMQ_PASSWORD=local-test-only
```

`EVILS_EVENTS_DISPATCHENABLED` aktiviert die standardmäßig ausgeschaltete Outbox-Auslieferung.
Nicht auf den entfernten Testserver anwenden. Exchange standardmäßig `evils.events`,
Binding `payment.#`. Consumer-Optionen: `RABBITMQ_URL` (Pflicht), `RABBITMQ_EXCHANGE`,
`RABBITMQ_QUEUE`, `INBOX_PATH`, `MAX_ATTEMPTS` (1–10, Standard 3), `HEALTH_HOST`, `HEALTH_PORT`.

## Zustellung und Fehler

- Bestätigung erst nach SQLite-Commit. Dubletten derselben ID und desselben Inhalts werden quittiert.
- Gleiche ID mit anderem Inhalt und ungültige JSON-Verträge kommen sofort in die Fehlerqueue.
- Temporäre SQLite-Fehler werden insgesamt höchstens dreimal versucht, mit kurzer Poll-Pause.
- Retry-/Fehlerkopien werden persistent und mit Publisher Confirm sowie Mandatory Routing gesendet.
  Erst nach Brokerbestätigung wird das Original quittiert. Bei Ausfall bleibt es unquittiert.
- Ein Absturz zwischen Kopie und Quittierung kann Dubletten erzeugen (At-least-once).
  Erfolgreiche SQLite-Verarbeitung bleibt idempotent. Retry-Grenzen gelten pro Zustellungszweig;
  Broker-Ausfälle können zusätzliche Zustellungen verursachen. Keine Exactly-once-Garantie für externe APIs.
- Eine Instanz pro SQLite-Datei betreiben. Externe Backend-Wirkungen benötigen eigene transaktionale
  Inbox-/Idempotenzlogik. Keine automatische Löschung der Inbox oder Fehlerqueue.

`/health` liefert 503 bei fehlender/veralteter Brokerverbindung, sonst 200. `/metrics` liefert
JSON: erfolgreiche Verarbeitungen, Dubletten, Fehlversuche, Fehlerquote pro Zustellung,
letzte erfolgreiche Verarbeitung und Anzahl wartender Nachrichten beider Queues.
Rückstände sind Momentaufnahmen ohne unquittierte Nachrichten; Zähler sind pro Prozess.
Eine gefüllte Fehlerqueue ist über `deadReady` sichtbar, macht die Verbindung selbst aber nicht ungesund.

Grundlagen: [RabbitMQ Acknowledgements](https://www.rabbitmq.com/docs/confirms),
[Pika BlockingConnection / Publisher Confirms](https://pika.readthedocs.io/en/stable/modules/adapters/blocking.html).
