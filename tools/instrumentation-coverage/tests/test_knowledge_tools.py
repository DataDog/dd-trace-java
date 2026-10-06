import importlib.util
import json
from collections import defaultdict
from pathlib import Path
import sys
import tempfile
import unittest

TOOLS = Path(__file__).parents[1]
sys.path.insert(0, str(TOOLS))


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


graph_query = load('graph_query', TOOLS / 'graph-query.py')
validator = load('knowledge_validator', TOOLS / 'validate-knowledge.py')
analyzer = load('flow_analyzer', TOOLS / 'scripts/join.py')


class KnowledgeToolsTest(unittest.TestCase):
    def setUp(self):
        self.raw = {'artifacts': [{'coordinate': 'lib-1'}], 'types': [], 'externalMethods': [],
                    'definedMethods': [{'id': name} for name in ('A', 'B', 'C')],
                    'calls': [{'from': a, 'to': b, 'opcode': opcode, 'line': line}
                              for a, b, opcode, line in [('A', 'B', 'INVOKEVIRTUAL', 10),
                                                        ('B', 'A', 'INVOKEVIRTUAL', 20),
                                                        ('B', 'C', 'LAMBDA_IMPLEMENTATION', 30)]]}
        self.graph = graph_query.Graph(self.raw)
        self.library = {'schemaVersion': 1, 'library': 'g:lib', 'reviewedVersion': '1'}
        self.observation = {'schemaVersion': 1, 'classes': ['A'], 'requiredTransformed': ['A']}
        self.flow = {'id': 'scenario', 'feature': 'Feature', 'variant': 'Variant',
                     'outcome': 'Outcome', 'status': 'draft', 'sourceIds': ['source'],
                     'steps': [{'id': name, 'anchor': name} for name in ('A', 'B', 'C')],
                     'identification': {'allOf': ['A']},
                     'completion': {'allOf': ['B'], 'optional': ['C']}}
        self.catalog = {'schemaVersion': 2, 'library': 'g:lib', 'version': '1',
                        'sources': [{'id': 'source'}], 'flows': [self.flow]}

    def family_catalog(self):
        return {'schemaVersion': 1, 'library': 'g:lib', 'version': '1',
                'status': 'draft', 'scope': 'Test scope', 'families': [{
                    'id': 'family', 'name': 'Family', 'classification': 'mapped',
                    'entryMethods': ['A'], 'flowIds': ['scenario'],
                    'sourceIds': ['source'], 'rationale': 'Declared scenario'}]}

    def test_missing_catalog_is_an_error(self):
        result = validator.validate(self.raw, self.library, self.catalog, self.observation)
        self.assertFalse(result['valid'])
        self.assertIn('Missing catalog.json', result['errors'][0])

    def test_path_handles_cycle_and_preserves_lambda_edge(self):
        path = self.graph.path('A', 'C', 10)
        self.assertEqual('STATIC_CANDIDATE_PATH', path['status'])
        self.assertEqual(['INVOKEVIRTUAL', 'LAMBDA_IMPLEMENTATION'], [e['opcode'] for e in path['edges']])
        self.assertEqual([10, 30], [e['line'] for e in path['edges']])

    def test_exhaustion_is_not_no_path(self):
        self.assertEqual('SEARCH_BUDGET_EXHAUSTED', self.graph.path('A', 'C', 1)['status'])
        self.assertEqual('NO_DECLARED_PATH', self.graph.path('C', 'A', 10)['status'])

    def test_unknown_method_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'Unknown method'):
            self.graph.path('missing', 'A', 10)

    def test_neighborhood_is_bounded(self):
        result = self.graph.neighborhood('A', 'callees', 5, 1)
        self.assertTrue(result['truncated'])
        self.assertEqual(1, len(result['edges']))

    def test_static_validation_never_approves_semantics(self):
        result = validator.validate(self.raw, self.library, self.catalog, self.observation, catalog=self.family_catalog())
        self.assertTrue(result['valid'])
        self.assertEqual('NOT_PERFORMED', result['semanticReview'])

    def test_catalog_assessment_exposes_unmapped_candidate_without_making_it_a_flow(self):
        catalog = {
            'schemaVersion': 1,
            'library': 'g:lib',
            'version': '1',
            'status': 'needs-review',
            'scope': 'Reviewed public async API families.',
            'families': [
                {
                    'id': 'mapped', 'name': 'Mapped behavior', 'classification': 'mapped',
                    'entryMethods': ['A'], 'flowIds': ['scenario'],
                    'sourceIds': ['source'], 'rationale': 'Represented by the declared flow.',
                },
                {
                    'id': 'candidate', 'name': 'Potential missing behavior',
                    'classification': 'candidate', 'entryMethods': ['C'], 'flowIds': [],
                    'sourceIds': ['source'], 'rationale': 'Public behavior needs semantic review.',
                },
            ],
        }

        result = validator.validate(
            self.raw, self.library, self.catalog, self.observation, catalog=catalog)

        self.assertTrue(result['valid'])
        self.assertEqual(1, result['catalogAssessment']['summary']['candidate'])
        self.assertEqual([], result['catalogAssessment']['unclassifiedFlowIds'])
        self.assertEqual([], catalog['families'][1]['flowIds'])

    def test_catalog_candidate_method_must_bind_to_graph(self):
        catalog = {
            'schemaVersion': 1,
            'library': 'g:lib',
            'version': '1',
            'status': 'draft',
            'scope': 'Reviewed public async API families.',
            'families': [{
                'id': 'candidate', 'name': 'Potential missing behavior',
                'classification': 'candidate', 'entryMethods': ['missing'], 'flowIds': [],
                'sourceIds': ['source'], 'rationale': 'Public behavior needs semantic review.',
            }],
        }

        result = validator.validate(
            self.raw, self.library, self.catalog, self.observation, catalog=catalog)

        self.assertFalse(result['valid'])
        self.assertTrue(any('absent from the graph' in error for error in result['errors']))

    def test_catalog_candidate_becomes_entry_evidence_flow(self):
        empty = lambda: {
            'root': 0, 'context': 0, 'scenarios': set(), 'evidence_sources': set(),
            'tests': {},
        }
        observations = defaultdict(empty)
        observations['C'] = {
            'root': 0, 'context': 1, 'scenarios': {'candidate test'},
            'evidence_sources': {'suite'},
            'tests': {'suite::candidate': {
                'root': 0, 'context': 1, 'name': 'candidate test', 'source': 'suite',
                'scenarioId': 'candidate', 'attribution': {'THREAD_LOCAL'},
                'attributionConfidence': {'EXACT'}, 'threads': {},
            }},
        }
        assessment = {'families': [{
            'id': 'candidate', 'name': 'Potential behavior', 'classification': 'candidate',
            'entryMethodBindings': [{'methodId': 'C', 'bindingStatus': 'RESOLVED'}],
            'rationale': 'Needs a complete flow.', 'sourceIds': ['source'],
        }]}
        result = {'flows': []}

        analyzer.append_candidate_flows(result, assessment, observations, {'C': {}})

        self.assertEqual(['candidate.candidate'], [flow['id'] for flow in result['flows']])
        self.assertEqual(1, len(result['flows'][0]['tests']))
        self.assertEqual(1, result['flows'][0]['coverage']['observed'])
        self.assertEqual('candidate-needs-review', result['flows'][0]['status'])

    def test_overlapping_completion_roles_are_rejected(self):
        self.flow['completion']['optional'] = ['B']
        with self.assertRaisesRegex(ValueError, 'completion roles'):
            validator.validate(self.raw, self.library, self.catalog, self.observation)

    def test_context_contract_requires_step_and_source_provenance(self):
        self.flow['contextContract'] = {
            'status': 'needs-review',
            'precondition': 'The operation starts under a non-root parent.',
            'expectations': [{
                'id': 'callback-entry',
                'stepIds': ['B'],
                'expected': 'PRESENT',
                'sourceIds': ['source'],
                'rationale': 'A repository callback test explicitly asserts active Context.',
            }],
        }
        self.assertTrue(
            validator.validate(self.raw, self.library, self.catalog, self.observation, catalog=self.family_catalog())['valid'])

        self.flow['contextContract']['expectations'][0]['sourceIds'] = ['missing']
        with self.assertRaisesRegex(ValueError, 'known sources'):
            validator.validate(self.raw, self.library, self.catalog, self.observation)

    def test_wrong_provenance_graph_is_rejected(self):
        result = validator.validate(self.raw, self.library, self.catalog, self.observation,
                                    {'schemaVersion': 1, 'graphSha256': 'old', 'claims': []}, 'new')
        self.assertFalse(result['valid'])

    def test_optional_method_never_becomes_required_completion(self):
        flow = dict(self.flow, feature='feature', variant='variant', outcome='outcome',
                    tests=[], coverage={}, transitions=[])
        for step in flow['steps']:
            step.update(matches=[], bindingStatus='RESOLVED', runtimeEvidence={'state': 'UNOBSERVED'})
        flow['expectedMethods'] = [
            {'methodId': name, 'owner': 'Owner', 'name': name, 'stepIds': [name],
             'bindingStatus': 'RESOLVED', 'runtimeEvidence': {'state': 'UNOBSERVED'}}
            for name in ('A', 'B', 'C')]
        result = {'library': 'g:lib', 'version': '1', 'contextEvidenceReports': []}
        task = analyzer.build_task_bundle(result, flow, {})
        self.assertEqual(['B'], [m['methodId'] for m in task['flowContract']['completionCheckpoints']])
        self.assertEqual(['C'], [m['methodId'] for m in task['flowContract']['optionalCompletionCheckpoints']])

    def test_task_bundle_preserves_context_contract(self):
        contract = {
            'status': 'needs-review',
            'precondition': 'Cancellation happens after the parent scope closes.',
            'expectations': [{
                'id': 'cancel-entry', 'stepIds': ['A'], 'expected': 'EITHER',
                'sourceIds': ['source'], 'rationale': 'Repository precedent allows root.',
            }],
        }
        flow = dict(self.flow, feature='feature', variant='variant', outcome='outcome',
                    tests=[], coverage={}, transitions=[], contextContract=contract)
        for step in flow['steps']:
            step.update(matches=[], bindingStatus='RESOLVED', runtimeEvidence={'state': 'UNOBSERVED'})
        flow['expectedMethods'] = []
        result = {'library': 'g:lib', 'version': '1', 'contextEvidenceReports': []}

        task = analyzer.build_task_bundle(result, flow, {})

        self.assertEqual(contract, task['flowContract']['contextContract'])

    def test_context_investigation_never_claims_an_instrumentation_fix(self):
        flow = dict(self.flow, feature='feature', variant='variant', outcome='outcome',
                    tests=[{'id': 'scenario'}], coverage={}, transitions=[], expectedMethods=[])
        for step in flow['steps']:
            step.update(matches=[], bindingStatus='RESOLVED', runtimeEvidence={'state': 'UNOBSERVED'})
        target = {'methodId': 'A', 'runtimeEvidence': {
            'state': 'ROOT_CONTEXT', 'contextEntries': 0, 'rootEntries': 2}}
        result = {'library': 'g:lib', 'version': '1', 'contextEvidenceReports': []}

        task = analyzer.build_investigation_task(
            result, flow, {}, 'INVESTIGATE_CONTEXT_GAP', [target])

        self.assertEqual('INVESTIGATE_CONTEXT_GAP', task['kind'])
        self.assertEqual('ROOT_CONTEXT', task['investigationTargets'][0]['state'])
        self.assertTrue(any('failing baseline' in item for item in task['acceptanceCriteria']))

    def test_runtime_join_uses_scenario_identity_instead_of_display_name(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / 'suite' / 'report.json'
            report.parent.mkdir()
            report.write_text(json.dumps({
                'methods': [{'method': 'example.Type.call()V', 'observations': [
                    {'scenarioId': 'junit:first', 'scenario': 'same display', 'count': 1,
                     'state': 'NON_ROOT_CONTEXT', 'attribution': 'THREAD_LOCAL',
                     'attributionConfidence': 'EXACT'},
                    {'scenarioId': 'junit:second', 'scenario': 'same display', 'count': 1,
                     'state': 'ROOT_CONTEXT', 'attribution': 'ACTIVE_TEST_WINDOW',
                     'attributionConfidence': 'TEMPORAL'}]}]
            }))

            observations, _, _ = analyzer.load_observations(Path(directory))
            tests = observations['example/Type#call()V']['tests']

            self.assertEqual({'suite::junit:first', 'suite::junit:second'}, set(tests))
            self.assertEqual(['EXACT'], sorted(tests['suite::junit:first']['attributionConfidence']))
            self.assertEqual(['TEMPORAL'],
                             sorted(tests['suite::junit:second']['attributionConfidence']))


if __name__ == '__main__':
    unittest.main()
