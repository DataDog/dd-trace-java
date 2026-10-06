#!/usr/bin/env python3
"""Compare unchanged RxJava 3 tests with/without the Jupiter observer; no flow catalog required."""
import json
from pathlib import Path
import shutil
import uuid
import xml.etree.ElementTree as ET

from workflow import ROOT, ENGINE, execute, read, write

MODULE = 'dd-java-agent/instrumentation/rxjava/rxjava-3.0'
PROJECT = ':' + MODULE.replace('/', ':')


def totals(directory):
    suites = [ET.parse(path).getroot() for path in sorted(directory.glob('TEST-*.xml'))]
    if not suites:
        raise ValueError('No test results: ' + str(directory))
    return {key: sum(int(suite.get(key, 0)) for suite in suites)
            for key in ('tests', 'failures', 'errors', 'skipped')}


def main():
    run = ROOT / MODULE / 'build/junit-coverage-validation' / uuid.uuid4().hex
    run.mkdir(parents=True)
    classes = ['io.reactivex.rxjava3.core.' + name
               for name in ('Observable', 'Flowable', 'Single', 'Maybe', 'Completable')]
    write(run / 'knowledge/library.json', {
        'schemaVersion': 1, 'library': 'io.reactivex.rxjava3:rxjava', 'reviewedVersion': '3.0.0',
        'artifactGroups': ['io.reactivex.rxjava3', 'org.reactivestreams'],
        'runtimeConfiguration': 'testRuntimeClasspath', 'testTask': 'test'})
    write(run / 'knowledge/observation.json', {
        'schemaVersion': 1, 'adapter': 'junit', 'classes': classes,
        'requiredTransformed': classes, 'attribution': 'serialized-test-window'})
    execute(ROOT / 'gradlew', '-p', ENGINE, 'observerJar', '--console=plain')
    execute(ROOT / 'gradlew', PROJECT + ':test', '--rerun', '-PtestJvm=21', '--console=plain')
    shutil.copytree(ROOT / MODULE / 'build/test-results/test', run / 'baseline-test-results')
    execute(ROOT / 'gradlew', '-I', ROOT / 'tools/instrumentation-coverage/collection.init.gradle',
            PROJECT + ':test', '-PcoverageModule=' + PROJECT, '-PcoverageRun=' + str(run),
            '-PtestJvm=21', '--console=plain', '--no-configuration-cache')
    baseline, observed = totals(run / 'baseline-test-results'), totals(run / 'test-results')
    if baseline != observed or observed['failures'] or observed['errors']:
        raise ValueError('Baseline/observed test outcomes differ or failed')
    reports = []
    scenario_ids = set()
    for path in sorted((run / 'observations').rglob('report.json')):
        value = read(path)
        execution = value['execution']
        if (not value['finalized'] or value['health']['errors'] or value['health']['droppedObservations']
                or execution['runId'] != run.name or execution['adapter'] != 'junit'
                or not execution['agentInstalled'] or not execution['agentTransformerInstalled']
                or execution['contextClassLoader'] != 'bootstrap'
                or not set(classes).issubset(execution['productionTransformedClasses'])):
            raise ValueError('Invalid collection: ' + str(path))
        for method in value['methods']:
            for observation in method['observations']:
                scenario_ids.add(observation.get('scenarioId', observation['scenario']))
        reports.append({'spec': execution['specName'], **value['summary']})
    if not reports or len(scenario_ids) != observed['tests'] - observed['skipped']:
        raise ValueError('Not every RxJava validation invocation has attributed observations')
    result = {'runId': run.name, 'baseline': baseline, 'observed': observed,
              'reports': reports, 'attributedInvocations': len(scenario_ids),
              'parameterizedInvocations': sum('test-template-invocation:' in scenario_id
                                              for scenario_id in scenario_ids)}
    write(run / 'validation.json', result)
    print(json.dumps(result, indent=2))
    print('Validation artifacts:', run)


if __name__ == '__main__':
    main()
