#!/usr/bin/env python3
"""Compatibility wrapper for the shared Pharos portal renderer."""
import argparse
import json
import sys
from pathlib import Path
HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parents[1]))
from pharos import render
from build import add_stages

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--report', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    data = json.loads(args.report.read_text())
    add_stages(data)
    render(data, args.output)
