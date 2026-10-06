"""Forward checks for premature delivery, narrowed scope and non-durable exclusions."""
import base64
import copy
from html.parser import HTMLParser
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parents[1]))
import assessment
import pharos
from test_pharos import fixture
from test_knowledge_tools import analyzer

spec = importlib.util.spec_from_file_location('delivery_workflow', Path(__file__).parents[1] / 'workflow.py')
workflow = importlib.util.module_from_spec(spec)
spec.loader.exec_module(workflow)


class PortalContent(HTMLParser):
    def __init__(self, text):
        super().__init__()
        self.embedded = ''
        self.notice = ''
        self.notice_tags = []
        self.in_data = self.in_notice = False
        self.feed(text)

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if attrs.get('id') == 'data':
            self.in_data = True
        if attrs.get('id') == 'delivery-notice':
            self.in_notice = True
        if self.in_notice:
            self.notice_tags.append(tag)

    def handle_endtag(self, tag):
        if tag == 'script':
            self.in_data = False
        if tag == 'aside':
            self.in_notice = False

    def handle_data(self, text):
        if self.in_data:
            self.embedded += text
        if self.in_notice:
            self.notice += text


class DeliveryProcessTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.source = self.root / 'Test.java'
        self.source.write_text('void example() {\n  assertEquals(expected, actual);\n}\n')
        self.report = self.root / 'input.json'

    def prepare(self, data):
        pharos.write(self.report, data)
        output = self.root / 'iteration'
        assessment.prepare(self.report, ['Test.java'], output, root=self.root)
        return output

    def author(self, output, bind=True):
        a = pharos.read(output / 'assessment.json')
        a['ready'] = True
        for item in a['behaviors']:
            item['assessmentSummary'] = 'No assertion support for this entry.'
        if bind:
            a['behaviors'][0].update(assessmentSummary='Exact returned value is asserted.', bindings=[dict(
                testId='test::one', evidenceStatus='BEHAVIOR_SUPPORTED',
                reason='Exact returned value is asserted.', citations=[dict(
                    sourceId='Test.java', startLine=2, endLine=2,
                    quote='assertEquals(expected, actual);')])])
        pharos.write(output / 'assessment.json', a)

    def test_many_passing_tests_do_not_finish_assertion_assessment(self):
        data = fixture()
        data['evidenceBasis'] = 'execution-anchors'
        data['testExecution']['passed'] = 145
        result = pharos.normalize(data)
        self.assertEqual('COLLECTED_NOT_ASSESSED', result['deliveryState']['assessmentStage'])
        self.assertEqual(0, result['deliveryState']['supportedBehaviors'])
        self.assertNotIn('taskComplete', result['deliveryState'])

    def stage_report(self, ungrouped=False):
        method = 'Example#call()V'
        raw = dict(definedMethods=[dict(id=method, owner='Example', name='call',
                    descriptor='()V', artifact='sample:library:1')], calls=[], externalMethods=[])
        step = dict(id='step-0', kind='library-method', anchor=method,
                    label='Subscribe to source', rationale='The source registers the callback here.',
                    sourceIds=['upstream'])
        presentation = (dict(mode='ungrouped', reason='The example has no defensible lifecycle grouping.')
                        if ungrouped else dict(mode='semantic'))
        manifest = dict(library='sample:library', version='1', sources=[dict(id='upstream')],
            flows=[dict(id='result', feature='Source', variant='Subscription', outcome='Registered',
                        steps=[step], stagePresentation=presentation, identification=dict(allOf=['step-0']),
                        completion=dict(allOf=[], anyOf=[], optional=[]))])
        observations, reports, inventory = analyzer.load_observations(None)
        return analyzer.analyze(raw, manifest, observations, reports, inventory, {}, None, dict(passed=1))

    def test_labels_survive_binding_assessment_render_and_replay(self):
        joined = self.stage_report()
        self.assertEqual('Subscribe to source', joined['flows'][0]['steps'][0]['label'])
        output = self.prepare(joined)
        self.author(output, bind=False)
        assessed = assessment.finish(output / 'session.json', self.root)
        replayed = pharos.render(assessed, self.root / 'replayed')
        expected = [dict(id='step-0', name='Subscribe to source',
                         rationale='The source registers the callback here.', sourceIds=['upstream'],
                         methods=['Example#call()V'])]
        self.assertEqual(expected, replayed['variants'][0]['stages'])
        portal = PortalContent((self.root / 'replayed/index.html').read_text())
        self.assertEqual(expected, json.loads(base64.b64decode(portal.embedded))['variants'][0]['stages'])
        self.assertEqual(assessed['reportIdentity'], replayed['reportIdentity'])

    def test_catalog_navigation_survives_binding_assessment_render_and_replay(self):
        joined = self.stage_report()
        navigation = dict(description='Registration behavior.', dimensions=[],
                          scenarios={'result': dict(label='Registers callback', values={})})
        joined['catalogAssessment'] = dict(navigationSchemaVersion=1, families=[dict(
            id='registration', name='Callback registration', classification='mapped',
            flowIds=['result'], navigation=navigation)])
        baseline = pharos.normalize(json.loads(json.dumps(self.stage_report(), sort_keys=True)))
        output = self.prepare(joined); self.author(output, bind=False)
        result = assessment.finish(output / 'session.json', self.root)
        replayed = pharos.render(result, self.root / 'replayed')
        self.assertEqual(navigation['scenarios'], replayed['catalogNavigation']['families'][0]['scenarios'])
        for field in ('id', 'references', 'methods', 'stages', 'associations', 'similarityCandidates'):
            self.assertEqual(baseline['variants'][0][field], replayed['variants'][0][field])
        portal = PortalContent((self.root / 'replayed/index.html').read_text())
        self.assertEqual(replayed['catalogNavigation'], json.loads(base64.b64decode(portal.embedded))['catalogNavigation'])
        del replayed['catalogNavigation']
        with self.assertRaisesRegex(ValueError, 'navigation lost'):
            pharos.render(replayed, self.root / 'rejected')

    def test_ungrouped_methods_survive_binding_assessment_and_replay(self):
        output = self.prepare(self.stage_report(ungrouped=True))
        self.author(output, bind=False)
        replayed = pharos.render(assessment.finish(output / 'session.json', self.root), self.root / 'replayed')
        variant = replayed['variants'][0]
        self.assertEqual([], variant['stages'])
        self.assertEqual(['Example#call()V'], variant['references'][0]['methods'])
        self.assertEqual('ungrouped', variant['stagePresentation']['mode'])

    def test_placeholder_in_joined_semantic_report_is_rejected(self):
        joined = self.stage_report()
        joined['flows'][0]['steps'][0]['label'] = 'Step 0'
        with self.assertRaisesRegex(ValueError, 'meaningful stage label'):
            self.prepare(joined)

    def test_legacy_unlabeled_run_renders_methods_without_fake_stages(self):
        joined = self.stage_report()
        flow = joined['flows'][0]
        flow.pop('stagePresentation')
        flow['steps'][0].pop('label')
        data = pharos.normalize(joined)
        self.assertEqual([], data['variants'][0]['stages'])
        self.assertEqual(['Example#call()V'], data['variants'][0]['references'][0]['methods'])

    def test_placeholder_in_portable_report_is_rejected_before_render(self):
        data = fixture()
        data['variants'][0]['stages'] = [dict(id='s', name='Step 0', methods=['Example#call()V'])]
        with self.assertRaisesRegex(ValueError, 'Meaningful stage label'):
            pharos.render(data, self.root / 'rejected')
        self.assertFalse((self.root / 'rejected/index.html').exists())

    def test_recollection_command_keeps_diagnostic_selection(self):
        run = self.root / 'run'
        data = fixture()
        pharos.write(run / 'manifest.json', dict(status='collected', module='example', runId='one'))
        pharos.write(run / 'knowledge/library.json', dict(library=data['library'], reviewedVersion=data['version']))
        scope = workflow.collection_scope(['Example.first', 'Example.second'], 'Known failures', 'User requested exclusion')
        pharos.write(run / 'collection-scope.json', scope)
        result = pharos.normalize(data, run)
        self.assertEqual(scope, result['collectionScope'])
        self.assertEqual([
            '--exclude-test', 'Example.first', '--exclude-test', 'Example.second',
            '--exclusion-reason', 'Known failures', '--exclusion-authorization', 'User requested exclusion'
        ], result['commands']['collect'][5:])

    def test_assessed_subset_cannot_erase_pending_catalog_family(self):
        data = fixture()
        candidate = copy.deepcopy(data['variants'][0])
        candidate.update(id='candidate.retry', name='Retry family')
        data['variants'].append(candidate)
        output = self.prepare(data)
        self.author(output)
        result = assessment.finish(output / 'session.json', self.root)
        state = result['deliveryState']
        self.assertEqual('ASSERTIONS_ASSESSED', state['assessmentStage'])
        self.assertEqual((1, 1, 1), (state['declaredBehaviors'], state['supportedBehaviors'], state['candidateFamilies']))
        self.assertEqual('NOT_ASSESSED', result['variants'][1]['evidenceStatus'])
        self.assertNotIn('taskComplete', state)

    def test_zero_supported_after_assessment_is_not_unassessed_collection(self):
        output = self.prepare(fixture())
        self.author(output, bind=False)
        result = assessment.finish(output / 'session.json', self.root)
        self.assertEqual('ASSERTIONS_ASSESSED', result['deliveryState']['assessmentStage'])
        self.assertEqual(0, result['deliveryState']['supportedBehaviors'])

    def test_exclusions_survive_assess_render_replay_and_do_not_shrink_denominator(self):
        data = fixture()
        unverified = copy.deepcopy(data['variants'][0])
        unverified.update(id='multicast', name='Independent subscription parents')
        data['variants'].append(unverified)
        pattern = '<img src=x onerror=alert(1)>.regression'
        data['collectionScope'] = workflow.collection_scope([pattern], 'Known failure', 'User requested diagnostic exclusion')
        output = self.prepare(data)
        self.author(output)
        result = assessment.finish(output / 'session.json', self.root)
        replayed = pharos.render(pharos.read(output / 'report/report.json'), self.root / 'replayed')
        self.assertEqual(result['reportIdentity'], replayed['reportIdentity'])
        self.assertEqual((2, 1, 1), tuple(replayed['deliveryState'][k] for k in
                         ('declaredBehaviors', 'supportedBehaviors', 'excludedTests')))
        portal = PortalContent((self.root / 'replayed/index.html').read_text())
        self.assertEqual(replayed, json.loads(base64.b64decode(portal.embedded)))
        self.assertEqual('', portal.notice)
        self.assertEqual([], portal.notice_tags)
        self.assertEqual(data['collectionScope'], replayed['collectionScope'])

    def test_exclusions_require_durable_reason_and_authorization(self):
        for reason, approval in [(None, None), ('failure', None), (None, 'user approved'), (' ', 'user approved')]:
            with self.assertRaisesRegex(ValueError, 'authorization record'):
                workflow.collection_scope(['Example.bad'], reason, approval)
        with self.assertRaisesRegex(ValueError, 'metadata requires'):
            workflow.collection_scope([], 'failure', 'approved')
        self.assertEqual([], workflow.collection_scope()['excludedTests'])

    def test_failed_parent_finding_survives_filtered_assessment_and_html_replay(self):
        data = fixture()
        finding = dict(id='hot-parent', description='Root-created Subject callback parent',
                       evidence='Expected parent P, actual null; original failed.xml',
                       precondition='Two independently parented subscriptions; emission after scopes close')
        data['catalogAssessment'] = dict(reconciliation=dict(status='unresolved', knownFindings=[finding]))
        data['collectionScope'] = workflow.collection_scope(['Example.hot'], 'Known regression', 'Explicit user request')
        output = self.prepare(data)
        self.author(output)
        result = assessment.finish(output / 'session.json', self.root)
        replayed = pharos.render(result, self.root / 'replayed')
        self.assertEqual([finding], replayed['catalogScope']['knownFindings'])
        portal = PortalContent((self.root / 'replayed/index.html').read_text())
        self.assertEqual('', portal.notice)
        self.assertEqual([], portal.notice_tags)
        self.assertEqual(replayed, json.loads(base64.b64decode(portal.embedded)))

    def test_forged_cached_delivery_state_is_recomputed(self):
        data = fixture()
        data['evidenceBasis'] = 'execution-anchors'
        data['deliveryState'] = dict(assessmentStage='ASSERTIONS_ASSESSED', supportedBehaviors=100)
        result = pharos.normalize(data)
        self.assertEqual('COLLECTED_NOT_ASSESSED', result['deliveryState']['assessmentStage'])
        self.assertEqual(0, result['deliveryState']['supportedBehaviors'])

    def test_changed_scope_cannot_reuse_prepared_assessment(self):
        output = self.prepare(fixture())
        self.author(output)
        changed = fixture()
        changed['collectionScope'] = workflow.collection_scope(['Example.bad'], 'failure', 'explicit user request')
        pharos.write(self.report, changed)
        with self.assertRaisesRegex(ValueError, 'Session input changed'):
            assessment.finish(output / 'session.json', self.root)

    def test_old_reports_do_not_acquire_fictitious_assessment_completion(self):
        result = pharos.normalize(fixture())
        self.assertEqual('ASSESSMENT_UNRECORDED', result['deliveryState']['assessmentStage'])

    def test_tampered_exclusion_scope_is_rejected_before_report_join(self):
        run = self.root / 'run'
        for area in ('knowledge', 'graph', 'observations', 'test-results'):
            workflow.write(run / area / 'evidence.json', {})
        for name in ('resolved.json', 'graph-identity.json'):
            workflow.write(run / name, {})
        workflow.write(run / 'collection-scope.json', workflow.collection_scope(['Example.bad'], 'failure', 'user approved'))
        manifest = {'status': 'collected'}
        workflow.seal_evidence(run, manifest)
        workflow.write(run / 'manifest.json', manifest)
        workflow.write(run / 'collection-scope.json', workflow.collection_scope())
        with patch.object(workflow, 'execute') as execute:
            with self.assertRaisesRegex(ValueError, 'evidence changed: collection-scope'):
                workflow.report(run)
            execute.assert_not_called()


if __name__ == '__main__':
    unittest.main()
