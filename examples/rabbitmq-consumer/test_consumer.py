import json
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import Mock

from consumer import InvalidEvent, Status, Store, deliver, validate


def event():
    return dict(id='test-1', schemaVersion=1, source='evils-ebics-server',
                type='payment.booked', aggregateType='payment', aggregateId='11',
                occurredAt='2026-09-24T14:00:00Z', data=dict(paymentId=11, kind='BOOKED'))


class ConsumerTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.path = self.directory.name + '/inbox.sqlite'
        self.store = Store(self.path)
        self.channel = Mock()
        self.status = Status()
        self.props = SimpleNamespace(headers={}, message_id='test-1')
        self.method = SimpleNamespace(delivery_tag=7)

    def tearDown(self):
        self.store.db.close()
        self.directory.cleanup()

    def send(self, body=None, store=None):
        deliver(self.channel, self.method, self.props,
                json.dumps(event()).encode() if body is None else body,
                store or self.store, self.status, 'test.queue', 3)

    def test_valid_and_duplicate_after_restart(self):
        self.send()
        self.store.db.close()
        self.store = Store(self.path)
        self.send()
        self.assertEqual(self.status.snapshot()['processed'], 1)
        self.assertEqual(self.status.snapshot()['duplicates'], 1)
        self.assertEqual(self.store.db.execute('SELECT count(*) FROM inbox').fetchone()[0], 1)
        self.assertEqual(self.channel.basic_ack.call_count, 2)

    def test_contract_rejects_bad_fields(self):
        for key, value in [('schemaVersion', True), ('schemaVersion', 2), ('data', None),
                           ('aggregateId', '12'), ('occurredAt', '2026-09-24'), ('id', '')]:
            with self.subTest(key=key, value=value):
                payload = event()
                payload[key] = value
                with self.assertRaises(InvalidEvent):
                    validate(json.dumps(payload).encode())

    def test_bad_json_goes_to_dead_before_ack(self):
        self.send(b'not json')
        self.assertEqual(self.channel.mock_calls[0][0], 'basic_publish')
        self.assertEqual(self.channel.basic_publish.call_args.args[1], 'test.queue.dead')
        self.channel.basic_ack.assert_called_once_with(7)

    def test_publish_failure_does_not_ack_original(self):
        self.channel.basic_publish.side_effect = RuntimeError('broker failure')
        with self.assertRaises(RuntimeError):
            self.send(b'invalid')
        self.channel.basic_ack.assert_not_called()

    def test_retry_limit(self):
        import sqlite3
        store = Mock()
        store.process.side_effect = sqlite3.OperationalError('locked')
        self.send(store=store)
        self.assertEqual(self.channel.basic_publish.call_args.args[1], 'test.queue')
        self.assertEqual(self.channel.basic_publish.call_args.args[3].headers['x-evils-attempt'], 2)
        self.props.headers = {'x-evils-attempt': 3}
        self.send(store=store)
        self.assertEqual(self.channel.basic_publish.call_args.args[1], 'test.queue.dead')

    def test_conflicting_id_is_not_processed(self):
        self.send()
        changed = event()
        changed['data']['kind'] = 'SIGNED'
        changed['type'] = 'payment.signed'
        self.send(json.dumps(changed).encode())
        self.assertEqual(self.status.snapshot()['dead'], 1)
        self.assertEqual(self.store.db.execute('SELECT kind FROM inbox').fetchone()[0], 'BOOKED')

    def test_health_stale_and_disconnected(self):
        import time
        self.assertFalse(self.status.snapshot()['healthy'])
        self.status.update(connected=True, checkedAt=time.time())
        self.assertTrue(self.status.snapshot()['healthy'])
        self.status.update(checkedAt=time.time() - 31)
        self.assertFalse(self.status.snapshot()['healthy'])


if __name__ == '__main__':
    unittest.main()
