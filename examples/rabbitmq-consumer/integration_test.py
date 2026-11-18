"""Local Compose integration check; restarts ONLY this example's services."""
import json
import subprocess
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timezone
from pathlib import Path

import pika

ROOT = Path(__file__).resolve().parent


def compose(*args):
    subprocess.run(['docker', 'compose', *args], cwd=ROOT, check=True, timeout=90)


def metrics():
    with urllib.request.urlopen('http://127.0.0.1:8091/metrics', timeout=3) as response:
        return json.load(response)


def wait_for(predicate, seconds=60):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        try:
            result = metrics()
            if predicate(result):
                return result
        except (OSError, ValueError):
            pass
        time.sleep(1)
    raise AssertionError('Consumer did not reach expected state')


def publish(*bodies):
    # Deliberately fixed local-only endpoint, never a production destination.
    params = pika.URLParameters('amqp://evils-demo:local-test-only@127.0.0.1:5673/%2F')
    params.socket_timeout = 5
    params.blocked_connection_timeout = 10
    with pika.BlockingConnection(params) as connection:
        channel = connection.channel()
        channel.confirm_delivery()
        for body in bodies:
            channel.basic_publish('evils.events', 'payment.booked', body,
                pika.BasicProperties(content_type='application/json', delivery_mode=2), mandatory=True)


def main():
    before = wait_for(lambda m: m['healthy'])
    event = dict(id='integration-' + str(uuid.uuid4()), schemaVersion=1,
                 source='evils-ebics-server', type='payment.booked', aggregateType='payment',
                 aggregateId='1', occurredAt=datetime.now(timezone.utc).isoformat(),
                 data=dict(paymentId=1, kind='BOOKED'))
    body = json.dumps(event).encode()
    publish(body, body, b'invalid-json')
    wait_for(lambda m: m['processed'] == before['processed'] + 1
             and m['duplicates'] == before['duplicates'] + 1
             and m['dead'] == before['dead'] + 1
             and m['deadReady'] == before['deadReady'] + 1)
    print('PASS: receive, deduplication, confirmed dead-letter delivery', flush=True)
    compose('restart', 'consumer')
    wait_for(lambda m: m['healthy'] and m['deliveries'] == 0)
    publish(body)
    wait_for(lambda m: m['duplicates'] == 1 and m['processed'] == 0)
    print('PASS: durable deduplication after consumer restart', flush=True)
    try:
        compose('stop', 'rabbitmq')
        wait_for(lambda m: not m['healthy'])
        try:
            urllib.request.urlopen('http://127.0.0.1:8091/health', timeout=3)
            raise AssertionError('Expected unhealthy HTTP status')
        except urllib.error.HTTPError as exc:
            assert exc.code == 503
        print('PASS: broker outage reported as HTTP 503', flush=True)
    finally:
        compose('start', 'rabbitmq')
    wait_for(lambda m: m['healthy'])
    publish(body)
    wait_for(lambda m: m['duplicates'] == 2 and m['processed'] == 0
             and m['deadReady'] == before['deadReady'] + 1)
    print('PASS: broker reconnect, durable DLQ, no duplicate business effect', flush=True)


if __name__ == '__main__':
    main()
