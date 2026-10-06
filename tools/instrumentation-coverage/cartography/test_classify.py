import unittest
from classify import cluster, jaccard, match, weights

class StatisticalAttributionTest(unittest.TestCase):
    def test_distinctive_methods_get_more_weight(self):
        w=weights([{'shared','rare'},{'shared'},{'shared'}])
        self.assertGreater(w['rare'],w['shared'])
    def test_unobservable_is_unknown_not_missing(self):
        r=match({'a','b'},{'a'},{'a'},{'a':1,'b':1})
        self.assertEqual(r['missingObservableMethods'],[])
        self.assertEqual(r['unknownReferenceMethods'],['b'])
        self.assertEqual(r['referenceAvailability'],.5)
    def test_single_common_method_cannot_be_strong_match(self):
        self.assertLess(match({'a'},{'a'},{'a'},{'a':1})['score'],.7)
    def test_extra_request_methods_allow_partial_reference_matching(self):
        r=match(set('abcdef'),set('abcdefghijk'),set('abcdefghijk'),{})
        self.assertEqual(r['referenceRecall'],1)
        self.assertGreater(r['score'],.7)
    def test_complete_link_does_not_chain_dissimilar_endpoints(self):
        vectors={'a':{'x','y'},'b':{'x','y','z'},'c':{'y','z'}}
        groups=cluster(vectors,{},.6)
        self.assertEqual(len(groups),2)
        self.assertFalse(any('a' in g and 'c' in g for g in groups))
        self.assertEqual(groups,cluster(dict(reversed(list(vectors.items()))),{},.6))
    def test_missing_method_reduces_similarity(self):
        self.assertLess(jaccard({'a'},{'a','b'},{}),jaccard({'a','b'},{'a','b'},{}))
if __name__=='__main__':unittest.main()
