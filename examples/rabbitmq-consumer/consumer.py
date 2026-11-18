"""Isolated reference backend: persist metadata, never execute payments."""
import hashlib
import json
import os
import sqlite3
import time
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from threading import Thread, Lock


class InvalidEvent(ValueError):
    pass


def validate(body):
    try:
        if len(body) > 65536:
            raise ValueError()
        event = json.loads(body)
        if not isinstance(event, dict) or type(event.get('schemaVersion')) is not int or event['schemaVersion'] != 1:
            raise ValueError()
        for key in ('id', 'source', 'type', 'aggregateType', 'aggregateId', 'occurredAt'):
            if not isinstance(event.get(key), str) or not 0 < len(event[key]) <= 256:
                raise ValueError()
        data = event['data']
        if not isinstance(data, dict) or type(data.get('paymentId')) is not int or data['paymentId'] <= 0:
            raise ValueError()
        kind = data.get('kind')
        if not isinstance(kind, str) or not kind or len(kind) > 128:
            raise ValueError()
        if event['source'] != 'evils-ebics-server' or event['aggregateType'] != 'payment':
            raise ValueError()
        if event['aggregateId'] != str(data['paymentId']) or event['type'] != 'payment.' + kind.lower():
            raise ValueError()
        if datetime.fromisoformat(event['occurredAt'].replace('Z', '+00:00')).tzinfo is None:
            raise ValueError()
        return event
    except (ValueError, TypeError, KeyError, UnicodeError) as exc:
        raise InvalidEvent('invalid-v1-envelope') from exc


class Store:
    def __init__(self, path):
        self.db = sqlite3.connect(path)
        self.db.execute('PRAGMA journal_mode=WAL')
        self.db.execute('CREATE TABLE IF NOT EXISTS inbox (id TEXT PRIMARY KEY, digest TEXT NOT NULL, payment_id INTEGER NOT NULL, kind TEXT NOT NULL, processed_at TEXT NOT NULL)')

    def process(self, event):
        digest = hashlib.sha256(json.dumps(event, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
        # The example business effect and deduplication marker are the same atomic row.
        with self.db:
            old = self.db.execute('SELECT digest FROM inbox WHERE id=?', (event['id'],)).fetchone()
            if old:
                if old[0] != digest:
                    raise InvalidEvent('event-id-conflict')
                return False
            self.db.execute('INSERT INTO inbox VALUES (?,?,?,?,?)', (event['id'], digest,
                event['data']['paymentId'], event['data']['kind'], datetime.now(timezone.utc).isoformat()))
        return True


class Status:
    def __init__(self):
        self.lock = Lock()
        self.values = dict(connected=False, processed=0, duplicates=0, failures=0, dead=0,
                           deliveries=0, lastSuccess=None, queueReady=None, deadReady=None, checkedAt=0)

    def update(self, **values):
        with self.lock:
            self.values.update(values)

    def increment(self, key):
        with self.lock:
            self.values[key] += 1

    def snapshot(self):
        with self.lock:
            values = dict(self.values)
        values['healthy'] = values['connected'] and time.time() - values['checkedAt'] < 30
        values['failureRate'] = values['failures'] / max(1, values['deliveries'])
        return values


def deliver(channel, method, properties, body, store, status, queue, max_attempts):
    import pika
    status.increment('deliveries')
    try:
        fresh = store.process(validate(body))
    except (InvalidEvent, sqlite3.OperationalError) as exc:
        status.increment('failures')
        headers = dict(properties.headers or {})
        attempt = headers.get('x-evils-attempt', 1)
        if type(attempt) is not int or attempt < 1:
            attempt = max_attempts
        dead = isinstance(exc, InvalidEvent) or attempt >= max_attempts
        headers['x-evils-attempt'] = attempt + 1
        headers['x-evils-error'] = 'invalid-event' if isinstance(exc, InvalidEvent) else 'storage-unavailable'
        # Confirm routed persistent copy BEFORE acknowledging original. A crash can
        # duplicate a delivery, but must not silently lose it.
        channel.basic_publish('', queue + '.dead' if dead else queue, body,
            pika.BasicProperties(content_type='application/json', delivery_mode=2,
                                 message_id=properties.message_id, headers=headers), mandatory=True)
        if dead:
            status.increment('dead')
    else:
        status.increment('processed' if fresh else 'duplicates')
        status.update(lastSuccess=datetime.now(timezone.utc).isoformat())
    channel.basic_ack(method.delivery_tag)


def main():
    import pika
    status = Status()
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            if self.path not in ('/health', '/metrics'):
                self.send_error(404)
                return
            snapshot = status.snapshot()
            body = json.dumps(snapshot).encode()
            self.send_response(200 if self.path == '/metrics' or snapshot['healthy'] else 503)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        def log_message(self, *_):
            pass
    server = ThreadingHTTPServer((os.getenv('HEALTH_HOST', '127.0.0.1'), int(os.getenv('HEALTH_PORT', '8091'))), Handler)
    Thread(target=server.serve_forever, daemon=True).start()
    queue = os.getenv('RABBITMQ_QUEUE', 'evils.backend.reference')
    exchange = os.getenv('RABBITMQ_EXCHANGE', 'evils.events')
    attempts = int(os.getenv('MAX_ATTEMPTS', '3'))
    if not 1 <= attempts <= 10:
        raise ValueError('MAX_ATTEMPTS must be 1..10')
    store = Store(os.getenv('INBOX_PATH', 'inbox.sqlite'))
    try:
        while True:
            connection = None
            try:
                params = pika.URLParameters(os.environ['RABBITMQ_URL'])
                params.heartbeat = 30
                params.blocked_connection_timeout = 10
                params.socket_timeout = 5
                connection = pika.BlockingConnection(params)
                channel = connection.channel()
                channel.exchange_declare(exchange, exchange_type='topic', durable=True)
                channel.queue_declare(queue, durable=True)
                channel.queue_declare(queue + '.dead', durable=True)
                channel.queue_bind(queue, exchange, 'payment.#')
                channel.confirm_delivery()
                # Poll one message at a time; no unbounded local backlog.
                while True:
                    ready = channel.queue_declare(queue, passive=True).method.message_count
                    dead = channel.queue_declare(queue + '.dead', passive=True).method.message_count
                    status.update(connected=True, queueReady=ready, deadReady=dead, checkedAt=time.time())
                    method, properties, body = channel.basic_get(queue, auto_ack=False)
                    if method:
                        deliver(channel, method, properties, body, store, status, queue, attempts)
                    connection.sleep(0.2 if method else 1)
            except (pika.exceptions.AMQPError, OSError):
                status.update(connected=False)
                print('Broker connection unavailable; retrying', flush=True)
                time.sleep(3)
            finally:
                if connection and connection.is_open:
                    connection.close()
    finally:
        server.shutdown()
        store.db.close()


if __name__ == '__main__':
    main()
