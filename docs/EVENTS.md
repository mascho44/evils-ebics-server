# EventPublisher für eine spätere Broker-Anbindung

Vorbereitet ist eine brokerunabhängige `EventPublisher`-Schnittstelle. Es wird noch **kein RabbitMQ-/Kafka-Client** gestartet und nichts extern versendet. Der konkrete Broker, Topic/Exchange, Routing, TLS und Zugangsdaten müssen vor Aktivierung festgelegt werden. Es gibt keinen HTTP-Webhook.

## Vertrag v1

Neue Zahlungsereignisse werden zusammen mit dem fachlichen Ereignis in derselben Datenbanktransaktion in `event_outbox` gespeichert. Ein Rollback entfernt beide. Bestehende historische Ereignisse werden nicht automatisch nachträglich veröffentlicht.

```json
{
  "id": "dauerhafte-eindeutige-event-id",
  "schemaVersion": 1,
  "source": "evils-ebics-server",
  "type": "payment.booked",
  "aggregateType": "payment",
  "aggregateId": "42",
  "occurredAt": "2026-09-24T05:00:00Z",
  "data": { "paymentId": 42, "kind": "BOOKED" }
}
```

`type` entspricht `payment.` plus dem kleingeschriebenen Ereignistyp. Erfasst werden beispielsweise CREATED, SIGNED, SCHEDULED, BOOKED, CREDIT_BOOKED, BOOKING_FAILED, VEU_PUBLISHED, VEU_SIGNED und Berichtsempfangsereignisse. Der erste Vertrag enthält bewusst keine Namen, IBANs, Beträge, Zahlungsdateien, Signaturen, Schlüssel oder freien Protokolltexte. Weitere Felder erst nach Abstimmung mit dem Backend ergänzen.

## Adapter und Versand

Genau ein Spring-Bean implementiert `EventPublisher.publish(IntegrationEvent)`. Er darf erst erfolgreich zurückkehren, nachdem der Broker die Nachricht gemäß seiner konfigurierten Zustellgarantie angenommen hat. Netzwerkfehler müssen als Exception weitergereicht werden; ein reiner asynchroner Sendeaufruf ohne Bestätigung genügt nicht. Adapter benötigen begrenzte Verbindungs- und Bestätigungs-Timeouts. Broker-Zugangsdaten gehören in die Serverkonfiguration, nicht in Events oder Git.

- `evils.events.dispatch-enabled=false` ist der Standard. Erst nach Adapter-Konfiguration bewusst auf `true` setzen (`EVILS_EVENTS_DISPATCH_ENABLED`). Ohne Adapter bleiben Events ebenfalls offen.
- `evils.events.poll-ms=5000`: Abfrageintervall; maximal 20 fällige Events je Lauf.
- `evils.events.max-attempts=10`: Höchstzahl fehlgeschlagener Versuche. Wiederholungen nach 5, 10, 20 Sekunden usw., maximal 1 Stunde.
- Zustände: PENDING, DELIVERED, DEAD. Fehler werden ohne möglicherweise vertrauliche Exception-Texte gespeichert. DEAD-Einträge bleiben zur Untersuchung erhalten; automatische Löschung oder manuelles Replay ist noch nicht implementiert.
- Admin-Status: `GET /admin/api/events/status` zeigt Aktivierung, Adapter-Verfügbarkeit und Zähler, keine Nutzdaten.

Die Zustellung ist **mindestens einmal**, nicht exakt einmal: Bei einem Abbruch zwischen Broker-Bestätigung und Datenbank-Commit kann dieselbe ID erneut gesendet werden. Das Backend muss die ID dauerhaft deduplizieren. Versand erfolgt außerhalb der Buchungstransaktion; Broker-Ausfälle verhindern daher keine Buchungen. Ein Outbox-Schreibfehler führt hingegen zum Rollback der Buchung, damit kein Ereignis verloren geht.

Eine globale Reihenfolge oder strenge Reihenfolge je Zahlung ist noch nicht zugesichert, insbesondere bei Wiederholungen. Für eine spätere Kafka-Partitionierung bietet sich `aggregateId` als Schlüssel an; ein RabbitMQ-Adapter kann `type` als Routing-Key verwenden. Die konkrete Zuordnung ist Teil der noch offenen Backend-Konfiguration. Retention und Alarmierung für wachsende PENDING-/DEAD-Bestände müssen im Betrieb ergänzt werden.

### RabbitMQ (optional)

Ein deaktivierter RabbitMQ-Adapter ist vorbereitet. Erst `EVILS_EVENTS_RABBIT_ENABLED=true` aktiviert ihn; ohne diese Einstellung wird keine Broker-Verbindung aufgebaut. Exchange, Routing-Präfix und die Standard-Spring-AMQP-Verbindungsparameter bleiben konfigurierbar.
