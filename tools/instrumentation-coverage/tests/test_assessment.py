"""The autonomous handoff must not silently promote stale or unrelated evidence."""
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).parents[1]))
import assessment
import pharos
from test_pharos import fixture


class AssessmentHandoffTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.source = self.root/'Test.java'
        self.source.write_text('void test() {\n  assertEquals(expected, actual);\n}\n')
        self.report = self.root/'input.json'
        pharos.write(self.report, fixture())
        self.output = self.root/'iteration-01'
        assessment.prepare(self.report, ['Test.java'], self.output, root=self.root)
        self.path = self.output/'assessment.json'

    def author(self):
        a = pharos.read(self.path)
        a['ready'] = True
        a['behaviors'][0].update(assessmentSummary='Result assertion inspected.', bindings=[dict(
            testId='test::one', evidenceStatus='BEHAVIOR_SUPPORTED', reason='Asserts returned result.',
            citations=[dict(sourceId='Test.java', startLine=2, endLine=2,
                            quote='assertEquals(expected, actual);')])])
        pharos.write(self.path, a)
        return a

    def test_incomplete_draft_cannot_render(self):
        with self.assertRaisesRegex(ValueError, 'incomplete'):
            assessment.finish(self.output/'session.json', self.root)
        self.assertFalse((self.output/'report').exists())

    def test_validated_binding_and_resume_handoff(self):
        self.author()
        result = assessment.finish(self.output/'session.json', self.root)
        self.assertEqual(result['variants'][0]['associations'][0]['testId'], 'test::one')
        self.assertEqual(result['reviewStatus'], 'LLM_DRAFT_NOT_HUMAN_REVIEWED')
        self.assertFalse(result['assertionAssessment']['semanticApproval'])
        self.assertEqual(pharos.read(self.output/'session.json')['phase'], 'investigate')
        self.assertEqual(result['variants'][0]['methods'], fixture()['variants'][0]['methods'])

    def test_refresh_drops_old_support_even_if_identity_still_matches(self):
        self.author()
        self.source.write_text(self.source.read_text()+'// new fixture detail\n')
        out=self.root/'iteration-02'
        assessment.prepare(self.report, [], out, previous=self.path, root=self.root)
        draft=pharos.read(out/'assessment.json'); dossier=pharos.read(out/'dossier.json')
        self.assertEqual(draft['behaviors'][0]['bindings'], [])
        self.assertFalse(draft['ready'])
        self.assertEqual(dossier['sourceChanges'][0]['status'], 'changed')
        self.assertTrue(dossier['previousBindings'][0]['identityStillPresent'])
        self.assertTrue(dossier['previousBindings'][0]['requiresReassessment'])

    def test_changed_source_blocks_render(self):
        self.author(); self.source.write_text('no assertion\n')
        with self.assertRaisesRegex(ValueError, 'Source changed'):
            assessment.finish(self.output/'session.json', self.root)

    def test_changed_report_blocks_resume(self):
        self.author(); data=fixture(); data['runCoverage']['observed']=0; pharos.write(self.report,data)
        with self.assertRaisesRegex(ValueError, 'Session input changed'):
            assessment.finish(self.output/'session.json', self.root)

    def test_cannot_remove_a_behavior_to_inflate_support(self):
        a=self.author(); a['behaviors']=[]; pharos.write(self.path,a)
        with self.assertRaisesRegex(ValueError, 'entire declared'):
            assessment.finish(self.output/'session.json', self.root)

    def test_invented_quote_and_test_are_rejected(self):
        a=self.author(); a['behaviors'][0]['bindings'][0]['citations'][0]['quote']='assertTrue(invented)'; pharos.write(self.path,a)
        with self.assertRaisesRegex(ValueError, 'quote/range'):
            assessment.finish(self.output/'session.json', self.root)
        a=self.author(); a['behaviors'][0]['bindings'][0]['testId']='foreign'; pharos.write(self.path,a)
        with self.assertRaisesRegex(ValueError, 'test identity'):
            assessment.finish(self.output/'session.json', self.root)

    def test_new_version_requires_kb_refresh(self):
        self.author(); data=fixture(); data['version']='2'; pharos.write(self.report,data)
        with self.assertRaisesRegex(ValueError, 'Library/version changed'):
            assessment.prepare(self.report, [], self.root/'next', previous=self.path, root=self.root)

    def test_prepare_preserves_existing_iteration(self):
        with self.assertRaisesRegex(ValueError, 'not empty'):
            assessment.prepare(self.report, ['Test.java'], self.output, root=self.root)

    def test_unassessed_prior_binding_is_not_still_called_supported(self):
        self.author(); current=assessment.finish(self.output/'session.json', self.root)
        pharos.write(self.report,current)
        out=self.root/'iteration-02'
        assessment.prepare(self.report, [], out, previous=self.path, root=self.root)
        a=pharos.read(out/'assessment.json');a['ready']=True;a['behaviors'][0]['assessmentSummary']='Needs further review.'
        pharos.write(out/'assessment.json',a)
        result=assessment.finish(out/'session.json',self.root)
        self.assertEqual(result['variants'][0]['associations'],[])
        self.assertEqual(result['variants'][0]['similarityCandidates'][0]['evidenceStatus'],'UNASSESSED_TEST')

    def test_cannot_turn_draft_into_human_approval(self):
        a=self.author();a['reviewStatus']='HUMAN_REVIEWED';pharos.write(self.path,a)
        with self.assertRaisesRegex(ValueError,'not human approval'):
            assessment.finish(self.output/'session.json',self.root)
