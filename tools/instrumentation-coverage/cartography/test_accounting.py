import json
import tempfile
import unittest
from pathlib import Path

from accounting import reconcile
from classify import join


class TestAccountingTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.run = Path(self.temp.name)
        (self.run/'resolved.json').write_text('{}')

    def results(self, target, cases):
        path = self.run/'test-results'/target/'TEST-Suite.xml'
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text('<testsuite>'+''.join(f'<testcase classname="Suite" name="{name}">{child}</testcase>' for name, child in cases)+'</testsuite>')

    def collector(self, target, names, observed=False):
        path = self.run/'observations'/target/'worker-1'/'Suite'/'report.json'
        path.parent.mkdir(parents=True, exist_ok=True)
        methods = [{'method': 'example.Library.call()V', 'observations': [
            {'count': 1, 'scenario': names[0], 'scenarioId': 'case-id', 'state': 'NON_ROOT_CONTEXT'}]}] if observed else []
        path.write_text(json.dumps({'scenarios': [{'name': n} for n in names], 'methods': methods}))

    def test_missing_collection_empty_windows_and_skips_are_retained(self):
        self.results('test', [('no-window', ''), ('empty', ''), ('skipped', '<skipped/>')])
        self.collector('test', ['empty'])
        self.results('forkedTest', [('no-collector', '')])
        result = reconcile(self.run, [])
        self.assertEqual(result['counts'], {'NO_TEST_WINDOW': 1, 'NO_LIBRARY_OBSERVATIONS': 1, 'SKIPPED': 1, 'NO_COLLECTION': 1})
        self.assertEqual(result['total'], 4)

    def test_identical_test_names_stay_separate_across_targets(self):
        for target in ('test', 'forkedTest'):
            self.results(target, [('same', '')])
            self.collector(target, ['same'], observed=True)
        obs, _, _ = join.load_observations(self.run/'observations')
        entries = obs['example/Library#call()V']['tests']
        self.assertEqual(len(entries), 2)
        self.assertEqual({v['target'] for v in entries.values()}, {'test', 'forkedTest'})
        classified = [{'target': 'test', 'suite': 'Suite', 'name': 'same', 'status': 'UNMATCHED'}]
        result = reconcile(self.run, classified)
        self.assertEqual(result['counts'], {'UNMATCHED': 1, 'NO_LIBRARY_OBSERVATIONS': 1})

    def test_duplicate_display_names_are_explicitly_ambiguous(self):
        self.results('test', [('same', ''), ('same', '')])
        result = reconcile(self.run, [])
        self.assertEqual(result['counts'], {'IDENTITY_AMBIGUOUS': 2})

    def test_orphan_classification_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'absent from XML'):
            reconcile(self.run, [{'target': 'test', 'suite': 'Suite', 'name': 'ghost'}])
