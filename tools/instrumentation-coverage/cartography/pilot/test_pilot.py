import copy
import json
import unittest
from pathlib import Path
from build import build, run_coverage, BASE, HERE


class BehavioralAssessmentTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.report=json.loads((BASE/'build/report/report.json').read_text())
        cls.packet=json.loads((BASE/'build/naming-evidence.json').read_text())
        cls.reference=json.loads((BASE/'build/reference.json').read_text())
        cls.assessment=json.loads((HERE/'assessment.json').read_text())
        cls.result=build(cls.report,cls.packet,cls.reference,cls.assessment)

    def test_similarity_does_not_establish_behavior(self):
        variant=next(v for v in self.result['variants'] if v['id']=='deferred-later')
        self.assertTrue(variant['similarityCandidates'])
        self.assertEqual(variant['evidenceStatus'],'NOT_ASSESSED')
        self.assertEqual(variant['associations'],[])

    def test_support_retains_target_identity_and_unassessed_dimensions(self):
        variant=self.result['variants'][0]
        self.assertEqual(len(variant['associations']),9)
        self.assertEqual({t['target'] for t in variant['associations']},{'test','forkedTest'})
        self.assertEqual(next(d for d in variant['dimensions'] if d['name']=='CompletionStage')['status'],'NOT_ASSESSED')
        self.assertEqual(self.result['defaultTask'],'INVESTIGATE_TEST_EVIDENCE')

    def test_stale_source_assessment_is_rejected(self):
        stale=copy.deepcopy(self.assessment);stale['sources'][0]['sha256']='invalid'
        with self.assertRaisesRegex(ValueError,'Source changed'):
            build(self.report,self.packet,self.reference,stale)

    def test_wrong_library_version_is_rejected(self):
        wrong={**self.report,'version':'6.2.0'}
        with self.assertRaisesRegex(ValueError,'Library/version mismatch'):
            build(wrong,self.packet,self.reference,self.assessment)


    def test_invented_quote_is_rejected(self):
        bad=copy.deepcopy(self.assessment);bad['claims'][0]['citations'][0]['quote']='not in source'
        with self.assertRaisesRegex(ValueError,'Citation quote'):
            build(self.report,self.packet,self.reference,bad)

    def test_unknown_test_identity_is_rejected(self):
        bad=copy.deepcopy(self.assessment);bad['variants'][0]['testBindings'][0]['testId']='invented'
        with self.assertRaisesRegex(ValueError,'Unknown test identity'):
            build(self.report,self.packet,self.reference,bad)

    def test_second_capability_uses_same_builder(self):
        a=json.loads((HERE/'request-body-assessment.json').read_text())
        r=build(self.report,self.packet,self.reference,a)
        self.assertEqual(r['capability']['id'],'request-body-binding')
        self.assertEqual(len(r['variants']),4)
        self.assertEqual(len(r['variants'][0]['associations']),3)
        self.assertEqual(r['variants'][1]['evidenceStatus'],'NOT_ASSESSED')
        self.assertTrue(any(c['scope']=='http-integration' for c in r['claims']))

    def test_changed_input_invalidates_assessment(self):
        bad={**self.report,'version':self.report['version'],'extraChangedEvidence':True}
        with self.assertRaisesRegex(ValueError,'Input evidence changed'):
            build(bad,self.packet,self.reference,self.assessment)

    def test_inventory_summary_does_not_count_entries_as_methods(self):
        report={'staticInventory':{'definedMethods':100,'artifacts':2},'methodInventory':[
            {'tests':{'a':{'context':10,'root':0},'b':{'context':0,'root':2}}},
            {'tests':{'a':{'context':0,'root':3}}},{'tests':{}}]}
        summary=run_coverage(report)
        self.assertEqual(summary['inventory'],3)
        self.assertEqual(summary['observed'],2)
        self.assertEqual(summary['mixed'],1)
        self.assertEqual(summary['root'],1)
        self.assertEqual(summary['unobserved'],1)
        self.assertEqual(summary['static']['definedMethods'],100)
