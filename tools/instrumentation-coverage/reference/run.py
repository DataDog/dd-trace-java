#!/usr/bin/env python3
"""Pin upstream sources and compare baseline/recorded executions before accepting any reference."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import uuid

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write(path, data):
    path.write_text(json.dumps(data, indent=2, sort_keys=True) + '\n')


def validate_links(window):
    landmarks = window['landmarks']
    registrations = {item['id']: item for item in window['registrations']}
    if len(registrations) != len(window['registrations']):
        raise ValueError('Duplicate registration identity: ' + window['id'])
    for registration in registrations.values():
        parent = landmarks.get(str(registration['parentEntry']))
        if not parent or parent['method'] != registration['method']:
            raise ValueError('Unresolved registration parent: ' + window['id'])
    for handoff in window['handoffs']:
        parent = registrations.get(handoff['registrationId'])
        execution = landmarks.get(str(handoff['executionId']))
        if not parent or not execution or execution['method'] != handoff['executionMethod']:
            raise ValueError('Unresolved handoff: ' + window['id'])
        if parent['method'] != handoff['registrationMethod'] or handoff['count'] < 1:
            raise ValueError('Inconsistent handoff: ' + window['id'])
    for link in window['carrierLinks']:
        if any(link[key] not in registrations for key in ('argumentRegistration', 'carrierRegistration')):
            raise ValueError('Unresolved constructor-argument ownership: ' + window['id'])


def validate(baseline, recordings):
    expected = json.loads((baseline / 'outcomes.json').read_text())
    artifacts = json.loads((baseline / 'runtime-manifest.json').read_text())
    if expected['failed'] or not expected['passed']:
        raise ValueError('Upstream baseline failed or empty')
    results = []
    for directory in recordings:
        actual = json.loads((directory / 'outcomes.json').read_text())
        if actual != expected:
            raise ValueError('Recorded test identities/outcomes differ from baseline: ' + str(directory))
        if json.loads((directory / 'runtime-manifest.json').read_text()) != artifacts:
            raise ValueError('Runtime artifacts changed')
        recorded = json.loads((directory / 'recording.json').read_text())
        if not recorded['installed'] or recorded['errors'] or recorded['unattributedEntriesDuringTest']:
            raise ValueError('Recorder health/attribution incomplete: ' + str(directory))
        expected_ids = {key for key, value in expected['invocations'].items() if value == 'passed'}
        windows = recorded['windows']
        if {window['id'] for window in windows} != expected_ids:
            raise ValueError('Recorded invocation accounting mismatch')
        if any(not window['finished'] or not window['methods'] for window in windows):
            raise ValueError('Unfinished or empty upstream invocation')
        if not recorded['transformed']:
            raise ValueError('Missing transformed inventory')
        for window in windows:
            validate_links(window)
        results.append(dict(directory=str(directory), invocations=len(windows),
                            handoffs=sum(len(w['handoffs']) for w in windows),
                            lateEntries=sum(w['lateEntries'] for w in windows)))
    return dict(status='RECORDER_CONSISTENCY_CHECKED', passed=expected['passed'],
                skipped=expected['skipped'], recordings=results,
                limitation='Registration/construction evidence still requires source review; this does not certify a complete causal reference or semantic catalog.')


def run(source, classes, repeats, harness, output_root=None):
    source = source.resolve(); classes = classes.resolve()
    harness = harness.resolve()
    if not (source / 'src/test/java').is_dir() or not classes.is_file() or repeats < 2:
        raise ValueError('Require upstream test sources, explicit class list and at least two recordings')
    if not (harness / 'src').is_dir() or not (harness / 'build.gradle').is_file():
        raise ValueError('Require a library-specific reference harness')
    output = (output_root or harness / 'build/runs').resolve() / uuid.uuid4().hex
    snapshot = output / 'upstream'
    snapshot.mkdir(parents=True)
    shutil.copytree(source / 'src/test', snapshot / 'src/test')
    shutil.copytree(source / 'src/main', snapshot / 'src/main')
    for name in ('docs',):
        if (source / name).is_dir():
            shutil.copytree(source / name, snapshot / name)
    for name in ('README.md', 'DESIGN.md', 'LICENSE', 'build.gradle', 'gradle.properties'):
        if (source / name).is_file():
            shutil.copy2(source / name, snapshot / name)
    shutil.copy2(classes, output / 'classes.txt')
    tool = output / 'tool'
    tool.mkdir()
    shutil.copytree(harness / 'src', tool / 'src')
    for name in ('build.gradle', 'settings.gradle'):
        shutil.copy2(harness / name, tool / name)
    shutil.copy2(HERE / 'run.py', tool / 'run.py')
    tool_sources = [dict(path=str(path.relative_to(tool)), sha256=digest(path))
                   for path in sorted(tool.rglob('*')) if path.is_file()]
    write(output / 'tool-manifest.json', tool_sources)
    sources = [dict(path=str(path.relative_to(snapshot)), sha256=digest(path))
               for path in sorted(snapshot.rglob('*')) if path.is_file()]
    write(output / 'source-manifest.json', sources)
    commands = []
    for name in ['baseline'] + ['recorded-' + str(i) for i in range(repeats)]:
        command = [str(ROOT / 'gradlew'), '-p', str(tool), 'referenceRun',
                   '-PupstreamSource=' + str(snapshot), '-PreferenceClasses=' + str(output / 'classes.txt'),
                   '-PreferenceOutput=' + str(output / name)]
        if name == 'baseline': command.append('-Pbaseline')
        commands.append(command)
        write(output / 'commands.json', commands)
        print('Running ' + name + ': ' + str(output), flush=True)
        with (output / (name + '.log')).open('w') as log:
            subprocess.run(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, check=True)
    if any(digest(snapshot / item['path']) != item['sha256'] for item in sources):
        raise ValueError('Upstream source snapshot changed')
    if any(digest(tool / item['path']) != item['sha256'] for item in tool_sources):
        raise ValueError('Recorder source snapshot changed')
    result = validate(output / 'baseline', [output / ('recorded-' + str(i)) for i in range(repeats)])
    result['classesSha256'] = digest(output / 'classes.txt')
    result['agentSha256'] = digest(tool / 'build/libs/pharos-reference-agent.jar')
    write(output / 'validation.json', result)
    sealed = [dict(path=str(path.relative_to(output)), sha256=digest(path))
              for path in sorted(output.rglob('*')) if path.is_file()
              and not path.is_relative_to(tool / 'build') and not path.is_relative_to(tool / '.gradle')]
    sealed.append(dict(path=str((tool / 'build/libs/pharos-reference-agent.jar').relative_to(output)),
                       sha256=result['agentSha256']))
    write(output / 'seal.json', dict(schemaVersion=1, files=sealed))
    print(json.dumps(dict(directory=str(output), **result), indent=2))
    return output


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--classes', type=Path, required=True)
    parser.add_argument('--harness', type=Path, required=True)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--repeats', type=int, default=2)
    args = parser.parse_args()
    run(args.source, args.classes, args.repeats, args.harness, args.output)
