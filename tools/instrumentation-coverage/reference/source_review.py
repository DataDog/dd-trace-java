#!/usr/bin/env python3
"""Print bounded, line-numbered original test examples for semantic review."""
import argparse
import re
from pathlib import Path


def excerpt(path, name):
    lines = path.read_text().splitlines()
    starts = [i for i, line in enumerate(lines)
              if re.search(r'^\s+(?:public\s+)?void\s+' + re.escape(name) + r'\s*\(', line)]
    if starts:
        member_indent = min(len(re.match(r'^\s*', lines[i]).group()) for i in starts)
        starts = [i for i in starts if len(re.match(r'^\s*', lines[i]).group()) == member_indent]
    if len(starts) != 1:
        raise ValueError(f'Expected one method: {path}#{name}: {starts}')
    start = starts[0]
    indent = re.match(r'^\s*', lines[start]).group()
    # Stop at the member's indentation, not a nested callback's brace.
    end = next(i for i in range(start + 1, len(lines)) if lines[i] == indent + '}') + 1
    if start > 0 and '@Test' in lines[start - 1]:
        start -= 1
    return '\n'.join(f'{i + 1}: {lines[i]}' for i in range(start, end))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', type=Path)
    parser.add_argument('--package', required=True, help='Upstream test package prefix')
    parser.add_argument('examples', nargs='+', help='Relative class#method within the package')
    args = parser.parse_args()
    for identity in args.examples:
        owner, name = identity.split('#')
        path = args.root / 'src/test/java' / args.package.replace('.', '/') / (owner.replace('.', '/') + '.java')
        print(identity + '\n' + excerpt(path, name) + '\n')
