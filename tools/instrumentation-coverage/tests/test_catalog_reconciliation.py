import json
import subprocess
import tempfile
import unittest
from pathlib import Path
import sys
sys.path.insert(0, str(Path(__file__).parents[1]))
from catalog_reconciliation import prepare, reconcile


class ReconciliationTest(unittest.TestCase):
    def setUp(self):
        self.flows = {'library': 'lib', 'version': '1', 'flows': [{'id': 'hot'}]}
        self.catalog = {'families': [{'id': 'hot', 'name': 'Hot', 'classification': 'mapped'}]}
        with tempfile.TemporaryDirectory() as d:
            p = Path(d); (p / 'PublishSubjectTest.java').write_text('test source')
            self.inputs, self.review = prepare(self.flows, p, [self.flows], [
                {'id': 'bug', 'description': 'Subscriber identity', 'evidence': 'reproducer'}])
        self.review['decisions'] = [{'signalId': s['id'], 'status': 'mapped',
                                   'flowIds': ['hot'], 'rationale': 'Reviewed counterpart'}
                                  for s in self.inputs['signals']]

    def test_reconciled_inputs(self):
        self.assertEqual('reconciled', reconcile(self.flows, self.catalog, self.inputs, self.review)['status'])

    def test_missing_inventory_rejected(self):
        with self.assertRaisesRegex(ValueError, 'missing'):
            reconcile(self.flows, self.catalog, None, None)

    def test_unaccounted_upstream_and_finding_remain_visible(self):
        self.review['decisions'] = [d for d in self.review['decisions'] if d['signalId'].startswith('prior:')]
        r = reconcile(self.flows, self.catalog, self.inputs, self.review)
        self.assertEqual('unresolved', r['status'])
        self.assertEqual(2, len(r['unresolvedSignals']))
        self.assertIn('finding:bug', r['unresolvedSignals'])

    def test_candidate_family_prevents_complete_scope(self):
        self.catalog['families'].append({'id': 'retry', 'name': 'Retry', 'classification': 'candidate'})
        self.assertEqual('unresolved', reconcile(self.flows, self.catalog, self.inputs, self.review)['status'])

    def test_dropping_flow_requires_reconciliation_even_if_methods_still_exist(self):
        self.flows['flows'] = [{'id': 'shared-method-path'}]
        with self.assertRaisesRegex(ValueError, 'Flow inventory changed'):
            reconcile(self.flows, self.catalog, self.inputs, self.review)
        self.review['flowIds'] = ['shared-method-path']
        self.review['decisions'] = []
        with self.assertRaisesRegex(ValueError, 'disappeared'):
            reconcile(self.flows, self.catalog, self.inputs, self.review)

    def test_removed_flow_can_be_explicitly_mapped_to_replacement(self):
        self.flows['flows'] = [{'id': 'replacement'}]
        self.review['flowIds'] = ['replacement']
        for d in self.review['decisions']: d['flowIds'] = ['replacement']
        self.assertEqual('reconciled', reconcile(self.flows, self.catalog, self.inputs, self.review)['status'])

    def test_changed_upstream_inventory_invalidates_review(self):
        self.inputs['signals'].append({'id': 'upstream:RetryTest.java', 'kind': 'upstream-test-source'})
        with self.assertRaisesRegex(ValueError, 'input identity'):
            reconcile(self.flows, self.catalog, self.inputs, self.review)

    def test_exclusion_requires_rationale_and_stays_visible(self):
        d = self.review['decisions'][0];d['status'] = 'excluded';d['rationale'] = ''
        with self.assertRaises(ValueError): reconcile(self.flows, self.catalog, self.inputs, self.review)
        d['rationale'] = 'Helper only, no standalone behavior'
        self.assertEqual(1, len(reconcile(self.flows, self.catalog, self.inputs, self.review)['exclusions']))

    def test_invalid_mapping_cannot_hide_finding(self):
        self.review['decisions'][-1]['flowIds'] = ['missing']
        with self.assertRaisesRegex(ValueError, 'existing flow'):
            reconcile(self.flows, self.catalog, self.inputs, self.review)

    def test_collection_validator_rejects_missing_reconciliation(self):
        from test_knowledge_tools import KnowledgeToolsTest
        fixture = KnowledgeToolsTest(); fixture.setUp()
        with tempfile.TemporaryDirectory() as d:
            p = Path(d)
            for name, data in [('library', fixture.library), ('flows', fixture.catalog),
                               ('observation', fixture.observation),
                               ('catalog', fixture.family_catalog()), ('graph', fixture.raw)]:
                (p / (name + '.json')).write_text(json.dumps(data))
            output = p / 'validation.json'
            result = subprocess.run([sys.executable, str(Path(__file__).parents[1] / 'validate-knowledge.py'),
                                     '--graph', str(p / 'graph.json'), '--knowledge', str(p),
                                     '--output', str(output)], capture_output=True)
            self.assertNotEqual(0, result.returncode)
            self.assertIn('reconciliation inputs/review missing', json.loads(output.read_text())['errors'][0])
