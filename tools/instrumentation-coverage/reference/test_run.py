import unittest

from run import validate_links


class ReferenceLinksTest(unittest.TestCase):
    def fixture(self):
        return dict(id='example', landmarks={'1': dict(method='subscribe'), '3': dict(method='run')},
                    registrations=[dict(id=2, parentEntry=1, method='subscribe')], carrierLinks=[],
                    handoffs=[dict(registrationId=2, executionId=3, registrationMethod='subscribe',
                                   executionMethod='run', count=7)])

    def test_valid_aggregate(self):
        validate_links(self.fixture())

    def test_unresolved_handoff_rejected(self):
        data = self.fixture()
        data['handoffs'][0]['registrationId'] = 99
        with self.assertRaises(ValueError):
            validate_links(data)

    def test_unresolved_parent_rejected(self):
        data = self.fixture()
        data['registrations'][0]['parentEntry'] = 99
        with self.assertRaises(ValueError):
            validate_links(data)

    def test_unresolved_ownership_rejected(self):
        data = self.fixture()
        data['carrierLinks'] = [dict(argumentRegistration=2, carrierRegistration=99)]
        with self.assertRaises(ValueError):
            validate_links(data)


if __name__ == '__main__':
    unittest.main()
