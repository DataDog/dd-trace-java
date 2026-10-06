"""Deterministic execution similarity; scores are not calibrated probabilities."""
from collections import Counter
from math import log1p, sqrt


def method_weights(references):
    """Downweight ubiquitous entries, counting each scenario once across alternatives."""
    frequency = Counter(method for methods in references for method in set(methods))
    size = len(references)
    return {method: 1 + log1p(size / count) for method, count in frequency.items()}


def similarity(reference, local, weights):
    left = {method: log1p(count) * weights.get(method, 1) for method, count in reference.items() if count > 0}
    right = {method: log1p(count) * weights.get(method, 1) for method, count in local.items() if count > 0}
    denominator = sqrt(sum(value * value for value in left.values()) *
                       sum(value * value for value in right.values()))
    return sum(value * right.get(method, 0) for method, value in left.items()) / denominator if denominator else 0


def score(reference, local, owner, weights):
    """Match an operator within a potentially larger composed local execution."""
    def belongs(method):
        return (method.startswith(owner + '#') or method.startswith(owner + '$')) and '#<' not in method

    scoped_reference = {method: count for method, count in reference.items() if belongs(method) and count > 0}
    scoped_local = {method: count for method, count in local.items() if belongs(method) and count > 0}
    if not scoped_reference or not scoped_local:
        return 0.0
    total = sum(weights.get(method, 1) ** 2 for method in scoped_reference)
    recall = sum(weights.get(method, 1) ** 2 for method in scoped_reference if method in scoped_local) / total
    operator = 0.65 * recall + 0.35 * similarity(scoped_reference, scoped_local, weights)
    result = 0.75 * operator + 0.25 * similarity(reference, local, weights)
    def terminals(methods):
        return {method.split('#', 1)[1].split('(', 1)[0] for method, count in methods.items()
                if count > 0 and method.split('#', 1)[1].split('(', 1)[0] in
                {'onSuccess', 'onComplete', 'onError'}}
    reference_terminal, local_terminal = terminals(reference), terminals(local)
    if reference_terminal and local_terminal and reference_terminal.isdisjoint(local_terminal):
        result *= 0.5
    return result


def classify(score_value, settings=None):
    settings = settings or {}
    likely, partial = settings.get('likely', 0.80), settings.get('partial', 0.50)
    if not 0 < partial < likely <= 1:
        raise ValueError('Classification thresholds must satisfy 0 < partial < likely <= 1')
    return 'Likely match' if score_value >= likely else 'Partial match' if score_value >= partial else 'No match'


def match_tests(alternatives, local, owner, weights, settings=None):
    matches = []
    for test_id, methods in sorted(local.items()):
        scores = [(score(item['methods'], methods, owner, weights), item) for item in alternatives]
        best_score, alternative = max(scores, key=lambda item: item[0])
        if best_score <= 0:
            continue
        stage_hits = []
        for stage in alternative['stages']:
            expected = set(stage['methods'])
            matched = sorted(expected.intersection(methods))
            stage_hits.append(dict(label=stage['label'], matched=matched,
                                   missing=sorted(expected.difference(methods))))
        matches.append(dict(testId=test_id, score=round(best_score, 6),
                            classification=classify(best_score, settings),
                            upstreamId=alternative['id'], recording=alternative['recording'],
                            stages=stage_hits))
    return sorted(matches, key=lambda item: (-item['score'], item['testId']))
