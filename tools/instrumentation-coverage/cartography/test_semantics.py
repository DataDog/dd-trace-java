import copy
import unittest

from semantics import digest, interpret


class InterpretationIntegrityTest(unittest.TestCase):
    def setUp(self):
        self.packet = {'families': [{'id': 'stable-id', 'examples': [{'name': 'Test#case'}],
                                    'methods': ['Library#run()V', 'Library#finish()V']}]}
        self.annotation = {'packetSha256': digest(self.packet), 'families': [
            {'id': 'stable-id', 'name': 'Run and finish', 'feature': 'Lifecycle',
             'summary': 'Source-supported description', 'evidenceTests': ['Test#case']}],
            'stageRules': [{'id': 'run', 'label': 'Run', 'pattern': '#run'},
                           {'id': 'finish', 'label': 'Finish', 'pattern': '#finish'}]}

    def test_evidence_change_invalidates_names(self):
        changed = copy.deepcopy(self.packet)
        changed['families'][0]['methods'].append('Library#error()V')
        with self.assertRaisesRegex(ValueError, 'evidence changed'):
            interpret(changed, self.annotation)

    def test_invented_source_cannot_support_label(self):
        self.annotation['families'][0]['evidenceTests'] = ['Test#invented']
        with self.assertRaisesRegex(ValueError, 'member-test evidence'):
            interpret(self.packet, self.annotation)

    def test_silent_method_omission_is_rejected(self):
        self.annotation['stageRules'].pop()
        with self.assertRaisesRegex(ValueError, 'Methods omitted'):
            interpret(self.packet, self.annotation)

    def test_label_change_does_not_change_family_identity_or_method_allocation(self):
        before = interpret(self.packet, self.annotation)
        self.annotation['families'][0]['name'] = 'Reworded behavior'
        after = interpret(self.packet, self.annotation)
        self.assertEqual(set(before), set(after))
        self.assertEqual(before['stable-id']['stages'], after['stable-id']['stages'])

    def test_duplicate_family_cannot_hide_an_omission(self):
        self.annotation['families'].append(copy.deepcopy(self.annotation['families'][0]))
        with self.assertRaisesRegex(ValueError, 'exactly once'):
            interpret(self.packet, self.annotation)


if __name__ == '__main__':
    unittest.main()
