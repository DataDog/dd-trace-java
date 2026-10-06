"""Evidence boundaries for the portable portal, independent of local saved runs."""
import copy
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('pharos', Path(__file__).parents[1] / 'pharos.py')
pharos = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pharos)


def fixture():
    return dict(schemaVersion=1, library='sample:library', version='1',
                runCoverage=dict(inventory=2, observed=1),
                testExecution=dict(passed=1, skipped=0, failures=0, errors=0),
                sources=[], claims=[], limitations=[], variants=[dict(
                    id='result', name='Return a result', trigger='A request', expected='A result',
                    associations=[], similarityCandidates=[dict(testId='test::one', name='one',
                        suite='Example', target='test', evidenceStatus='SIMILAR_EXECUTION_ONLY')],
                    references=[dict(name='Reference', methods=['Example#call()V'])],
                    methods={'Example#call()V':dict(observable=True, tests={'test::one':dict(context=2, root=1)})})])


class PortalTest(unittest.TestCase):
    def test_legacy_report_has_unknown_scope_even_with_all_methods_hit(self):
        data = pharos.normalize(fixture())
        self.assertEqual('unknown', data['catalogScope']['status'])

    def test_reconciliation_and_known_findings_survive_assessment_normalization(self):
        data = fixture()
        scope = {'status': 'unresolved', 'unresolvedSignals': ['upstream:hot'],
                 'knownFindings': [{'id': 'bug', 'description': 'Distinct parents'}]}
        data['catalogAssessment'] = {'reconciliation': scope}
        self.assertEqual(scope, pharos.normalize(data)['catalogScope'])

    def test_render_is_deterministic_and_offline(self):
        with tempfile.TemporaryDirectory() as tmp:
            first, second = Path(tmp)/'first', Path(tmp)/'second'
            pharos.render(fixture(), first); pharos.render(fixture(), second)
            self.assertEqual((first/'index.html').read_bytes(), (second/'index.html').read_bytes())
            self.assertEqual((first/'report.json').read_bytes(), (second/'report.json').read_bytes())
            self.assertNotIn('__PHAROS_MARK__', (first/'index.html').read_text())

    def test_similarity_cannot_become_assessed_support(self):
        data=fixture();v=data['variants'][0];v['associations']=v['similarityCandidates'];v['similarityCandidates']=[]
        with self.assertRaisesRegex(ValueError, 'promoted'): pharos.normalize(data)

    def test_static_anchor_matches_remain_unassessed(self):
        joined=dict(schemaVersion=2,library='other:library',version='2',testExecution={'passed':1},
                    methodInventory=[dict(id='Example#call()V',tests={'test::one':dict(context=1,root=0)})],
                    flows=[dict(id='flow',feature='Feature',variant='Variant',outcome='Outcome',
                                expectedMethods=[{'methodId':'Example#call()V'}], steps=[],
                                tests=[dict(id='test::one',name='one',suite='Example',attributionConfidence=['TEMPORAL'])])])
        data=pharos.normalize(joined);v=data['variants'][0]
        self.assertEqual(v['associations'],[])
        self.assertEqual(v['similarityCandidates'][0]['evidenceStatus'],'EXECUTION_ANCHORS_ONLY')
        self.assertEqual(v['similarityCandidates'][0]['attributionConfidence'],['TEMPORAL'])
        self.assertEqual(data['runCoverage']['observed'],1)

    def test_unknown_reference_method_is_rejected(self):
        data=fixture();data['variants'][0]['references'][0]['methods'].append('Unknown#call()V')
        with self.assertRaisesRegex(ValueError, 'eligibility'): pharos.normalize(data)

    def test_unknown_schema_is_not_silently_upgraded(self):
        data=fixture();data['schemaVersion']=99
        with self.assertRaisesRegex(ValueError, 'schema'): pharos.normalize(data)

    def test_failed_run_is_not_a_successful_report(self):
        data=fixture();data['testExecution']['failures']=1
        with self.assertRaisesRegex(ValueError, 'Failed'): pharos.normalize(data)

    def test_invalid_counts_and_unknown_test_are_rejected(self):
        data=fixture();data['variants'][0]['methods']['Example#call()V']['tests']['test::one']['root']=-1
        with self.assertRaisesRegex(ValueError, 'nonnegative'): pharos.normalize(data)
        data=fixture();data['variants'][0]['methods']['Example#call()V']['tests']['foreign']={}
        with self.assertRaisesRegex(ValueError, 'identity'): pharos.normalize(data)

    def test_comparison_refuses_denominator_or_version_changes(self):
        before=pharos.normalize(fixture());after=copy.deepcopy(before)
        after['version']='2'
        with self.assertRaisesRegex(ValueError, 'identical'): pharos.compare(before,after)
        after=copy.deepcopy(before);after['variants'][0]['methods']['Example#call()V']['observable']=False
        with self.assertRaisesRegex(ValueError, 'scope changed'): pharos.compare(before,after)

    def test_new_assertion_support_is_distinct_from_execution_gain(self):
        before=pharos.normalize(fixture());after=copy.deepcopy(before);v=after['variants'][0]
        v['associations']=v['similarityCandidates'];v['similarityCandidates']=[]
        v['associations'][0]['evidenceStatus']='BEHAVIOR_SUPPORTED'
        delta=pharos.compare(before,after)['changedBehaviors'][0]
        self.assertEqual(delta['before']['observedMethods'],delta['after']['observedMethods'])
        self.assertEqual(delta['before']['assessedTests'],[])
        self.assertEqual(delta['after']['assessedTests'],['test::one'])

    def test_run_metadata_cannot_be_attached_to_another_library(self):
        with tempfile.TemporaryDirectory() as tmp:
            run=Path(tmp)
            pharos.write(run/'manifest.json', {'status':'collected','module':'example'})
            pharos.write(run/'knowledge/library.json', {'library':'foreign:library','reviewedVersion':'1'})
            with self.assertRaisesRegex(ValueError, 'identity differ'):
                pharos.normalize(fixture(),run)

    def test_failed_run_metadata_cannot_be_attached(self):
        with tempfile.TemporaryDirectory() as tmp:
            run=Path(tmp)
            pharos.write(run/'manifest.json', {'status':'failed','module':'example'})
            pharos.write(run/'knowledge/library.json', {'library':'sample:library','reviewedVersion':'1'})
            with self.assertRaisesRegex(ValueError, 'collected run'):
                pharos.normalize(fixture(),run)
