#!/usr/bin/env python3
"""Materialize explicit authored upstream selections, without inferring scenarios from test names."""
import argparse
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent


def examples(flow, prefix=''):
    return [prefix + value for value in flow.get('tests', [flow.get('test')])]


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--plan', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    plan = json.loads(args.plan.read_text())
    selections = list(dict.fromkeys(item for family in plan['families'] for flow in family['flows']
                                   for item in examples(flow, plan['testPackage'])))
    output = args.output
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text('\n'.join(selections) + '\n')
    print(output)
