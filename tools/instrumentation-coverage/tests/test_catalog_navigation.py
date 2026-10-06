"""Authored grouping is mandatory and cannot hide or merge scenario evidence."""
import copy
import json
from pathlib import Path
import subprocess
import unittest
from catalog_navigation import project, validate_report


class CatalogNavigationTest(unittest.TestCase):
    def setUp(self):
        self.family = dict(id='opaque-family', name='Callback scheduling', classification='mapped',
            flowIds=['opaque-a', 'opaque-b'], navigation=dict(
                description='Subscription and delivery boundaries.',
                dimensions=[dict(id='boundary', label='Execution boundary', values=[
                    dict(id='subscribe', label='Scheduled subscription'),
                    dict(id='deliver', label='Scheduled delivery')])],
                scenarios={'opaque-a': dict(label='Success', values=dict(boundary='subscribe')),
                           'opaque-b': dict(label='Success', values=dict(boundary='deliver'))}))

    def test_opaque_ids_and_same_label_in_different_filters_are_valid(self):
        projected = project([self.family], {'opaque-a', 'opaque-b'})
        validate_report(projected, {'opaque-a', 'opaque-b'})
        self.assertEqual(self.family['navigation']['scenarios'], projected['families'][0]['scenarios'])

    def test_missing_metadata_rejected(self):
        del self.family['navigation']
        with self.assertRaisesRegex(ValueError, 'authored catalog navigation required'):
            project([self.family], {'opaque-a', 'opaque-b'})

    def test_orphan_and_ambiguous_membership_rejected(self):
        with self.assertRaisesRegex(ValueError, 'orphan scenarios'):
            project([self.family], {'opaque-a', 'opaque-b', 'opaque-c'})
        other = copy.deepcopy(self.family); other['id'] = 'another-family'
        with self.assertRaisesRegex(ValueError, 'ambiguous'):
            project([self.family, other], {'opaque-a', 'opaque-b'})

    def test_missing_scenario_and_unknown_dimension_rejected(self):
        scenarios = self.family['navigation']['scenarios']
        scenarios['opaque-a']['values']['boundary'] = 'invented'
        with self.assertRaisesRegex(ValueError, 'unknown navigation dimension value'):
            project([self.family], {'opaque-a', 'opaque-b'})
        del scenarios['opaque-a']
        with self.assertRaisesRegex(ValueError, 'exactly the family scenarios'):
            project([self.family], {'opaque-a', 'opaque-b'})

    def test_duplicate_dimension_and_placeholder_labels_rejected(self):
        navigation = self.family['navigation']
        navigation['dimensions'] *= 2
        with self.assertRaisesRegex(ValueError, 'duplicate navigation dimension'):
            project([self.family], {'opaque-a', 'opaque-b'})
        navigation['dimensions'] = navigation['dimensions'][:1]
        navigation['dimensions'][0]['label'] = 'Dimension 0'
        with self.assertRaisesRegex(ValueError, 'navigation dimension'):
            project([self.family], {'opaque-a', 'opaque-b'})

    def test_direct_browsing_is_explicit_and_labels_distinguish_rows(self):
        nav = self.family['navigation']; nav['dimensions'] = []
        for scenario in nav['scenarios'].values(): scenario['values'] = {}
        with self.assertRaisesRegex(ValueError, 'indistinguishable'):
            project([self.family], {'opaque-a', 'opaque-b'})
        nav['scenarios']['opaque-b']['label'] = 'Error'
        validate_report(project([self.family], {'opaque-a', 'opaque-b'}), {'opaque-a', 'opaque-b'})

    def test_empty_family_rejected(self):
        self.family['flowIds'] = []; self.family['navigation']['scenarios'] = {}
        with self.assertRaisesRegex(ValueError, 'exactly the family scenarios'):
            project([self.family], set())

    def test_shared_viewer_enforces_metadata_without_library_specific_rules(self):
        navigation = project([self.family], {'opaque-a', 'opaque-b'})
        report = dict(variants=[dict(id='opaque-a'), dict(id='opaque-b')],
                      catalogAssessment=dict(navigationSchemaVersion=1), catalogNavigation=navigation)
        asset = Path(__file__).parents[1] / 'portal/catalog-navigation.js'
        cases = [report]
        missing = copy.deepcopy(report); del missing['catalogNavigation']; cases.append(missing)
        orphan = copy.deepcopy(report); orphan['variants'].append(dict(id='orphan')); cases.append(orphan)
        ambiguous = copy.deepcopy(report)
        ambiguous['catalogNavigation']['families'].append(copy.deepcopy(navigation['families'][0]))
        ambiguous['catalogNavigation']['families'][1]['id'] = 'second'; cases.append(ambiguous)
        unknown = copy.deepcopy(report)
        unknown['catalogNavigation']['families'][0]['scenarios']['opaque-a']['values']['boundary'] = 'invalid'
        cases.append(unknown)
        placeholder = copy.deepcopy(report)
        placeholder['catalogNavigation']['families'][0]['dimensions'][0]['label'] = 'Dimension 0'
        cases.append(placeholder)
        lost = copy.deepcopy(report)
        del lost['catalogNavigation']['families'][0]['scenarios']['opaque-a']; cases.append(lost)
        legacy = copy.deepcopy(report); del legacy['catalogNavigation']; del legacy['catalogAssessment']
        cases.append(legacy)
        script = (asset.read_text() + '\nconst cases=' + json.dumps(cases) + ';\n'
                  'console.log(JSON.stringify(cases.map(r=>{try{PharosNavigation.validate(r);return true;}catch(e){return false;}})));')
        result = subprocess.run(['node', '-e', script], check=True, capture_output=True, text=True)
        self.assertEqual([True, False, False, False, False, False, False, True], json.loads(result.stdout))
