import copy
import unittest

from report import authored_stage_groups, canonical, observable_method, portal_data, reference_class, reference_stage_groups, validate_plan


class ReportContractTest(unittest.TestCase):
    def fixture(self):
        return dict(families=[dict(id='source', description='A source family', dimensions=['Type'],
                                  flows=[dict(id='value', label='Value', values=['Single'])])])

    def test_canonical_observation_matches_graph_identity(self):
        self.assertEqual('io/reactivex/rxjava3/core/Single#call()V',
                         canonical('io.reactivex.rxjava3.core.Single.call()V'))

    def test_valid_explicit_navigation(self):
        validate_plan(self.fixture())

    def test_missing_dimension_rejected(self):
        plan = self.fixture()
        plan['families'][0]['flows'][0]['values'] = []
        with self.assertRaises(ValueError):
            validate_plan(plan)

    def test_duplicate_family_owner_rejected(self):
        plan = self.fixture()
        second = copy.deepcopy(plan['families'][0])
        second['id'] = 'other'
        plan['families'].append(second)
        with self.assertRaises(ValueError):
            validate_plan(plan)

    def test_placeholder_rejected(self):
        plan = self.fixture()
        plan['families'][0]['flows'][0]['label'] = 'Step 0'
        with self.assertRaises(ValueError):
            validate_plan(plan)

    def test_authored_non_rx_stages_preserve_all_methods_once(self):
        definitions = [dict(label='Execute SQL', description='Prepare and execute SQL', selectors=['#execute'])]
        groups = authored_stage_groups(['h2/Statement#execute()V', 'h2/Row#map()V'], definitions)
        self.assertEqual(['Execute SQL', 'Supporting library methods'], [group[0] for group in groups])
        self.assertEqual(['h2/Statement#execute()V', 'h2/Row#map()V'], [method for group in groups for method in group[1]])

    def test_numbered_stage_rejected(self):
        plan = self.fixture()
        plan['stageDefinitions'] = [dict(label='Step 0', description='Meaning', selectors=['#execute'])]
        with self.assertRaises(ValueError):
            validate_plan(plan)

    def test_without_authored_stages_methods_are_not_given_lifecycle_roles(self):
        methods = ['db/Query#execute()V', 'db/Query#dispose()V', 'db/Pool#open()V']
        groups = reference_stage_groups(methods, methods[:2], None)
        self.assertEqual(['Reference methods'], [group[0] for group in groups])
        self.assertEqual(sorted(methods[:2]), groups[0][1])

    def test_authored_stages_are_used_for_non_reactive_methods(self):
        methods = ['db/Query#execute()V', 'db/Pool#open()V']
        definitions = [dict(label='Execute SQL', description='Run the query', selectors=['#execute'])]
        self.assertEqual(authored_stage_groups(methods, definitions),
                         reference_stage_groups(methods, methods[:1], definitions))

    def test_observation_eligibility_does_not_depend_on_callback_names(self):
        for name in ['execute', 'run', 'onError', 'dispose']:
            self.assertTrue(observable_method(dict(name=name, access=1)))
        for access in [0x1000, 0x400, 0x100, 0x40]:
            self.assertFalse(observable_method(dict(name='execute', access=access)))
        self.assertFalse(observable_method(dict(name='<init>', access=1)))

    def test_ungrouped_portal_retains_methods_without_stage_cards(self):
        method = 'db/Query#execute()V'
        flow = dict(id='query', label='Execute', values=['Query'], classification='Likely match', score=1,
                    stagePresentation=dict(mode='ungrouped'), matches=[], examples=[dict(
                        id='upstream', stages=[dict(label='Reference methods', methods=[dict(
                            id=method, entriesByTest={'local': dict(total=1)})])],
                        provenance={}, source=dict(text='test'), implementation=dict(text='source'))])
        data = dict(families=[dict(id='sql', name='SQL', description='Queries', dimensions=['Operation'],
                                  flows=[flow])], tests={}, library='DB', version='1',
                    classificationSettings={}, ourOutcomes=dict(tests=1, skipped=0),
                    referenceRun='reference', localRun='local', scope={}, referenceValidation=dict(passed=1), metric='hits')
        projected = portal_data(data)
        self.assertEqual([], projected['variants'][0]['stages'])
        self.assertEqual([method], projected['variants'][0]['references'][0]['methods'])
        self.assertIn(method, projected['variants'][0]['methods'])
        self.assertEqual(dict(observed=1, inventory=1), projected['runCoverage'])

    def test_neutral_class_field_and_legacy_alias(self):
        self.assertEqual('db/Query', reference_class(dict(id='sql', referenceClass='db/Query')))
        self.assertEqual('db/Query', reference_class(dict(id='sql', operator='db/Query')))
        with self.assertRaises(ValueError):
            reference_class(dict(id='sql', referenceClass='db/Query', operator='db/Pool'))
        with self.assertRaises(ValueError):
            reference_class(dict(id='sql'))
