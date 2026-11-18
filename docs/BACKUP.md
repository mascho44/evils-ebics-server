# Sicherung und Wiederherstellung

Die Testsuite läuft bei jedem Push und Pull Request in GitHub Actions (`.github/workflows/test.yml`) und lokal mit `mvn verify`; die Backup-Skripte werden dort mit `bash -n` auf Syntaxfehler geprüft.

Die Skripte benötigen Linux, Bash, Docker Compose, OpenSSL, tar und sha256sum. Im Deployment-Verzeichnis ausführen. Sicherungen enthalten Datenbank, vorhandene Compose-Dateien, `.env` und gegebenenfalls die gebaute Anwendung. Damit werden auch geschützte Bankschlüssel und ihre vorhandene Serverkonfiguration erfasst.

## Einmalige Vorbereitung

Eine zufällige lange Backup-Passphrase in einer separaten Datei mit Dateirechten `0600` hinterlegen. Die Datei nicht ins Repository oder ins Backup-Verzeichnis legen. Eine zusätzliche Kopie in einem Passwortmanager/offline aufbewahren. Ohne diese Passphrase ist das verschlüsselte Backup nicht wiederherstellbar.

## Backup und Probe

```bash
bash scripts/backup.sh /absoluter/pfad/zu/einer/neuen/sicherung /absoluter/pfad/backup.passphrase
bash scripts/restore-check.sh /absoluter/pfad/zu/einer/neuen/sicherung/backup.tar.gz.enc /absoluter/pfad/backup.passphrase
```

Das Zielverzeichnis darf noch nicht existieren. Die PostgreSQL-Sicherung ist transaktionskonsistent. Das Archiv wird mit AES-256-CBC/PBKDF2 verschlüsselt; SHA-256-Prüfsummen erkennen versehentliche Beschädigungen, sind aber kein Authentizitätsnachweis gegenüber Angreifern. Nur eigene, vertrauenswürdige Backups verwenden und zusätzlich außerhalb des Servers aufbewahren.

Die Probe stellt das Backup in einem neuen PostgreSQL-16-Container ohne Netzwerk und ohne veröffentlichte Ports wieder her. Sie prüft Import und zentrale Tabellen. Der Container und temporäre Klartextdateien werden anschließend entfernt. Die laufende Datenbank wird weder angehalten noch überschrieben. Die Probe allein beweist noch nicht die komplette Funktionsfähigkeit eines wiederhergestellten EBICS-Servers.

## Tatsächliche Wiederherstellung

1. Gewünschte Sicherung mit `restore-check.sh` prüfen und den aktuellen Stand nochmals sichern.
2. Neue, getrennte Serverumgebung vorbereiten; das verschlüsselte Archiv dort mit denselben OpenSSL-Parametern entschlüsseln.
3. Datenbank in eine **leere** PostgreSQL-16-Datenbank mit `pg_restore --no-owner --no-privileges --exit-on-error` importieren.
4. Gesicherte Konfiguration, insbesondere `EVILS_CRYPTO_MASTER_SECRET`, sowie die passende Anwendungsversion einsetzen. Geheimnisse nicht ausgeben oder einchecken.
5. Anmeldung, Entschlüsselung der Bankschlüssel und EBICS-Abruf prüfen. Erst danach den Reverse Proxy auf die wiederhergestellte Umgebung umstellen.

Es gibt absichtlich keinen ungesicherten GUI-Knopf, der die laufende Datenbank überschreibt. Terminaufträge können nach dem Start sofort fällig werden; deshalb bei Wiederherstellungsproben die Anwendung nicht unkontrolliert starten.
