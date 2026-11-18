# EVILS EBICS Server v0.6.0

Stand vom 24.09.2026. Ausschließlich Testsimulator, keine Freigabe für echten Zahlungsverkehr.

## Enthalten

- Persistenter Ledger und Test-Zahlungsverkehr mit VEU, Zahlungsereignissen, HAC und pain.002.
- Interoperabilität im Test bestätigt: S-Firm-Abruf und -Transfer erfolgreich; Subsembly-Abruf und -Transfer ebenfalls bestätigt.
- Konfigurierbare tägliche Testbuchungen, Kontoauswahl im Ledger und erweiterte Admin-Protokolle.
- Transaktionale Event-Outbox mit Wiederholungen, Admin-Ansicht und auditiertem DEAD-Replay.
- Optionaler RabbitMQ-Adapter mit Brokerbestätigung; standardmäßig keine externe Auslieferung.
- Separater lokaler Backend-Consumer mit dauerhafter Dublettenerkennung, Fehlerwarteschlange und Statusendpunkten.
- Sicherungs-/Wiederherstellungswerkzeuge und aktualisierte Roadmap einschließlich Mandantenfähigkeit.

## Artefakte

Die ausführbare JAR benötigt Java 21 oder neuer. `SHA256SUMS` enthält ihre Prüfsumme.
Quellcode einschließlich Consumer und Startanleitung liegt im GitHub-Quellarchiv des Tags.
Zugangsdaten, Datenbanken und lokal erzeugte Testdaten sind nicht Teil des Releases.

## Verifikation und Grenzen

- Maven-Testprüfung und sieben Consumer-Einzeltests.
- Lokaler RabbitMQ-Integrationstest: Empfang, Dublettenschutz nach Neustart, Fehlerqueue,
  Ausfallerkennung und Wiederverbindung.
- Expliziter Java-Outbox-Test: ein lokaler 10-Euro-Entwurf erzeugt `CREATED`; Auslieferung
  und erneute Zustellung werden geprüft. Keine Freigabe oder Buchung dieses Entwurfs.
- Kein vollständiger EBICS-Konformitätsnachweis; GitHub-CI liegt weiterhin nur als Vorlage vor.
- Referenz-Consumer ist lokal getestet, nicht auf dem entfernten Testserver bereitgestellt.
- Veröffentlichung allein aktualisiert keine laufende Installation. Vor einem Deployment sichern;
  bestehende Konfiguration und Crypto-Master-Secret beibehalten. Hinweise in README und Changelog beachten.
