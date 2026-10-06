"""Boundary tests: stale/mixed evidence must never become a current report."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('workflow', Path(__file__).parents[1] / 'workflow.py')
workflow = importlib.util.module_from_spec(spec)
spec.loader.exec_module(workflow)


class WorkflowTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.run = Path(self.temporary.name)
        for area in ('knowledge', 'graph', 'observations', 'test-results'):
            workflow.write(self.run / area / 'evidence.json', {'area': area})
        for name in ('resolved.json', 'graph-identity.json'):
            workflow.write(self.run / name, {})
        hashes = {str(p.relative_to(self.run)): workflow.digest(p) for p in self.run.rglob('*.json')}
        self.manifest = {'status': 'collected', 'evidenceHashes': hashes}
        workflow.write(self.run / 'manifest.json', self.manifest)

    @patch.object(workflow, 'execute')
    def test_unchanged_run_can_be_replayed(self, execute):
        workflow.report(self.run)
        self.assertEqual(execute.call_count, 2)
        self.assertIn(workflow.TOOL / "pharos.py", execute.call_args.args)

    @patch.object(workflow, 'execute')
    def test_modified_evidence_is_rejected_before_join(self, execute):
        workflow.write(self.run / 'observations/evidence.json', {'different': True})
        with self.assertRaisesRegex(ValueError, 'evidence changed'):
            workflow.report(self.run)
        execute.assert_not_called()

    @patch.object(workflow, 'execute')
    def test_added_foreign_report_is_rejected(self, execute):
        workflow.write(self.run / 'observations/foreign/report.json', {})
        with self.assertRaisesRegex(ValueError, 'inventory changed'):
            workflow.report(self.run)
        execute.assert_not_called()

    @patch.object(workflow, 'execute')
    def test_failed_run_cannot_be_replayed(self, execute):
        self.manifest['status'] = 'failed'
        workflow.write(self.run / 'manifest.json', self.manifest)
        with self.assertRaisesRegex(ValueError, 'successfully collected'):
            workflow.report(self.run)
        execute.assert_not_called()

    def test_version_mismatch_is_not_implicitly_rebound(self):
        library = {'schemaVersion': 1, 'library': 'g:a', 'reviewedVersion': '1'}
        observation = {'schemaVersion': 1}
        flows = {'schemaVersion': 2, 'library': 'g:a', 'version': '2'}
        with self.assertRaisesRegex(ValueError, 'identity differ'):
            workflow.validate_knowledge(library, flows, observation)

    def test_manifest_preserves_all_selected_test_tasks(self):
        module = workflow.ROOT / 'dd-java-agent/instrumentation'
        library = {'testTasks': ['test', 'forkedTest']}
        observation = {'attribution': 'serialized-test-window'}

        manifest = workflow.base_manifest(self.run, module, library, observation)

        self.assertEqual('test', manifest['testTask'])
        self.assertEqual(['test', 'forkedTest'], manifest['testTasks'])

    def test_predicate_cannot_reference_absent_step(self):
        library = {'schemaVersion': 1, 'library': 'g:a', 'reviewedVersion': '1'}
        observation = {'schemaVersion': 1, 'classes': ['A'], 'requiredTransformed': ['A']}
        flows = {'schemaVersion': 2, 'library': 'g:a', 'version': '1', 'flows': [
            {'id': 'flow', 'feature': 'Feature', 'variant': 'Variant', 'outcome': 'Outcome',
             'steps': [{'id': 'a'}], 'identification': {'allOf': ['missing']}}]}
        with self.assertRaisesRegex(ValueError, 'absent steps'):
            workflow.validate_knowledge(library, flows, observation)

    def test_duplicate_human_facing_flow_labels_are_rejected(self):
        library = {'schemaVersion': 1, 'library': 'g:a', 'reviewedVersion': '1'}
        observation = {'schemaVersion': 1, 'classes': ['A'], 'requiredTransformed': ['A']}
        base = {'feature': 'Unary RPC', 'variant': 'Client stub', 'outcome': 'completed',
                'steps': [{'id': 'a'}], 'identification': {'allOf': ['a']},
                'completion': {'allOf': [], 'anyOf': [], 'optional': []}}
        flows = {'schemaVersion': 2, 'library': 'g:a', 'version': '1', 'flows': [
            {'id': 'blocking', **base}, {'id': 'async', **base}]}

        with self.assertRaisesRegex(ValueError, 'Duplicate human-facing flow labels'):
            workflow.validate_knowledge(library, flows, observation)

    def test_compare_reports_is_versioned_and_flow_scoped(self):
        baseline = self.run / 'baseline.json'
        candidate = self.run / 'candidate.json'
        workflow.write(baseline, {
            'library': 'g:a', 'version': '1', 'flows': [
                {'id': 'covered', 'tests': [{'id': 'one'}], 'coverage': {'observed': 2}},
                {'id': 'changed', 'tests': [], 'coverage': {'observed': 0}}]})
        workflow.write(candidate, {
            'library': 'g:a', 'version': '1', 'flows': [
                {'id': 'covered', 'tests': [{'id': 'one'}], 'coverage': {'observed': 2}},
                {'id': 'changed', 'tests': [{'id': 'two'}], 'coverage': {'observed': 3}}]})

        result = workflow.compare_reports(baseline, candidate)

        self.assertEqual(1, result['unchangedFlows'])
        self.assertEqual(['changed'], [change['flowId'] for change in result['changedFlows']])
        self.assertEqual(1, result['changedFlows'][0]['after']['matchingTests'])

    def test_compare_rejects_different_library_versions(self):
        baseline = self.run / 'baseline.json'
        candidate = self.run / 'candidate.json'
        workflow.write(baseline, {'library': 'g:a', 'version': '1', 'flows': []})
        workflow.write(candidate, {'library': 'g:a', 'version': '2', 'flows': []})
        with self.assertRaisesRegex(ValueError, 'different library identities'):
            workflow.compare_reports(baseline, candidate)

    def test_required_transforms_can_be_satisfied_across_specifications(self):
        run = self.run / 'aggregate-transform-run'
        (run / 'observations').mkdir(parents=True)
        workflow.write(run / 'observations/one/report.json', {
            'finalized': True, 'health': {'errors': [], 'droppedObservations': 0},
            'execution': {'runId': run.name, 'productionTransformedClasses': ['A']}})
        workflow.write(run / 'observations/two/report.json', {
            'finalized': True, 'health': {'errors': [], 'droppedObservations': 0},
            'execution': {'runId': run.name, 'productionTransformedClasses': ['B']}})

        reports = workflow.validate_observation_reports(run, ['A', 'B'])

        self.assertEqual(2, len(reports))

    def test_missing_required_transform_fails_the_complete_run(self):
        run = self.run / 'missing-transform-run'
        workflow.write(run / 'observations/one/report.json', {
            'finalized': True, 'health': {'errors': [], 'droppedObservations': 0},
            'execution': {'runId': run.name, 'productionTransformedClasses': ['A']}})

        with self.assertRaisesRegex(ValueError, 'required types: B'):
            workflow.validate_observation_reports(run, ['A', 'B'])


if __name__ == '__main__':
    unittest.main()
