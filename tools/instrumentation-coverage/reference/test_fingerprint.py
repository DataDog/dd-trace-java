import unittest

from fingerprint import classify, match_tests, method_weights, score, similarity


class FingerprintTest(unittest.TestCase):
    def test_identical_execution_scores_one(self):
        methods = {'lib/Source#subscribe()V': 1, 'lib/Source#onSuccess()V': 3}
        self.assertAlmostEqual(1, score(methods, methods, 'lib/Source', method_weights([methods])))

    def test_other_operator_cannot_match_through_shared_methods(self):
        reference = {'lib/Source#subscribe()V': 1, 'lib/Common#call()V': 1}
        local = {'lib/Other#subscribe()V': 1, 'lib/Common#call()V': 1}
        self.assertEqual(0, score(reference, local, 'lib/Source', method_weights([reference])))

    def test_terminal_method_distinguishes_success_from_error(self):
        success = {'lib/Source#subscribe()V': 1, 'lib/Source$Observer#onSuccess()V': 1}
        error = {'lib/Source#subscribe()V': 1, 'lib/Source$Observer#onError()V': 1}
        weights = method_weights([success, error])
        self.assertGreater(score(success, success, 'lib/Source', weights),
                           score(error, success, 'lib/Source', weights))

    def test_composition_does_not_require_single_label(self):
        a = {'lib/A#subscribe()V': 1}
        b = {'lib/B#subscribe()V': 1}
        local = dict(a, **b)
        weights = method_weights([a, b])
        self.assertEqual('Likely match', classify(score(a, local, 'lib/A', weights)))
        self.assertEqual('Likely match', classify(score(b, local, 'lib/B', weights)))

    def test_alternatives_are_not_union_paths(self):
        alternatives = [dict(id='one', recording='r0', methods={'lib/A#one()V': 1}, stages=[]),
                        dict(id='two', recording='r1', methods={'lib/A#two()V': 1}, stages=[])]
        matches = match_tests(alternatives, {'test': {'lib/A#two()V': 1}}, 'lib/A', {})
        self.assertEqual('two', matches[0]['upstreamId'])
        self.assertEqual(1, matches[0]['score'])

    def test_stage_hits_are_per_test_not_suite_union(self):
        reference = {'lib/A#one()V': 1, 'lib/A#two()V': 1}
        alternative = dict(id='ref', recording='r0', methods=reference,
                           stages=[dict(label='Delivery', methods=list(reference))])
        matches = match_tests([alternative], {'a': {'lib/A#one()V': 1}, 'b': {'lib/A#two()V': 1}}, 'lib/A', {})
        self.assertTrue(all(len(item['stages'][0]['matched']) == 1 and len(item['stages'][0]['missing']) == 1
                            for item in matches))

    def test_common_methods_receive_lower_weights(self):
        weights = method_weights([{'shared', 'rare'}, {'shared'}, {'shared'}])
        self.assertGreater(weights['rare'], weights['shared'])

    def test_shared_operator_cannot_hide_contradicting_terminal_entries(self):
        reference = {'lib/A#subscribe()V': 1, 'lib/Observer#onError()V': 1}
        local = {'lib/A#subscribe()V': 1, 'lib/Observer#onSuccess()V': 1}
        self.assertLess(score(reference, local, 'lib/A', method_weights([reference])), .5)

    def test_empty_and_thresholds(self):
        self.assertEqual(0, similarity({}, {}, {}))
        self.assertEqual('No match', classify(0))
        self.assertEqual('Partial match', classify(.5))
        self.assertEqual('Likely match', classify(.8))
        with self.assertRaises(ValueError):
            classify(.5, {'partial': .9, 'likely': .8})
