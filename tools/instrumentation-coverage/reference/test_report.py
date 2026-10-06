import copy
import unittest

from report import canonical, validate_plan


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
