"""Source-backed test experiments, independent of statistical classification confidence."""

def build(flow, vectors, weights, target):
    examples = []
    by_method = {m['methodId']: m for m in flow['expectedMethods']}
    for source in flow['semanticInterpretation']['examples']:
        observed = vectors[source['testId']]
        stages = []
        for stage in flow['steps']:
            methods = sorted(set(stage['matches']) & observed, key=lambda m: (-weights.get(m, 1), m))
            if methods:
                stages.append(dict(id=stage['id'], label=stage['label'], methods=[
                    dict(methodId=m, owner=by_method[m]['owner'], name=by_method[m]['name'],
                         referenceSupport=by_method[m]['referenceSupport']) for m in methods]))
        examples.append(dict(**source, landmarks=stages))
    return dict(schemaVersion=1, taskId='exercise.'+flow['id'], kind='ADD_OR_STRENGTHEN_TEST',
                status='proposed-source-backed-experiment', downloadFilename=flow['id']+'-test-task.json',
                target=target, feature=flow['feature'], name=flow['variant'],
                summary=flow['semanticInterpretation']['summary'], examples=examples,
                candidateTests=[dict(id=t['id'],name=t['name'],suite=t['suite'],target=t.get('target')) for t in flow['tests']],
                instructions=[
                    'Choose one upstream example and inspect its body, fixtures and assertions at the linked source.',
                    'Inspect candidate tests first. Reuse or strengthen an existing test if it already exercises this behavior.',
                    'Adapt that example through the existing instrumentation-test harness and real library APIs. Preserve its distinguishing inputs and verify its outcome.',
                    'Where a parent operation is meaningful, verify expected parentage, scope restoration and isolation using existing repository contracts.',
                    'Rerun the module allTests task with coverage collection. Compare the chosen example landmarks against the new observations and report remaining gaps.'],
                acceptanceCriteria=[
                    'The intended behavioral outcome is asserted, not inferred from similarity or method presence.',
                    'Existing assertions still pass; compare per-target results and collection health.',
                    'Explain reached, absent and outside-scope landmarks. Do not require the union of all example variants.',
                    'Context expectations come from a verified scenario contract; root observations alone are not failures.'],
                constraints=[
                    'Do not modify production instrumentation. If an assertion exposes a gap, preserve the reproducer and report it.',
                    'Methods shown were recorded in that upstream example; they are investigation targets, not mandatory contracts.',
                    'Landmark grouping is conceptual, not a measured async call sequence.',
                    'No match is not proof of missing behavior; small families cannot satisfy the current four-method similarity threshold.'])
