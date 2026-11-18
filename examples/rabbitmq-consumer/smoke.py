"""Publish an isolated test event twice plus malformed JSON; inspect /metrics."""
import json
import os
import uuid
from datetime import datetime, timezone
import pika

identifier = 'smoke-' + str(uuid.uuid4())
event = dict(id=identifier, schemaVersion=1, source='evils-ebics-server',
             type='payment.booked', aggregateType='payment', aggregateId='1',
             occurredAt=datetime.now(timezone.utc).isoformat(), data=dict(paymentId=1, kind='BOOKED'))
with pika.BlockingConnection(pika.URLParameters(os.environ['RABBITMQ_URL'])) as connection:
    channel = connection.channel()
    channel.confirm_delivery()
    for body in (json.dumps(event).encode(), json.dumps(event).encode(), b'invalid-json'):
        channel.basic_publish('evils.events', 'payment.booked', body,
                              pika.BasicProperties(delivery_mode=2, content_type='application/json'), mandatory=True)
print('Published: expect processed +1, duplicates +1, dead +1. No payment was executed.')
