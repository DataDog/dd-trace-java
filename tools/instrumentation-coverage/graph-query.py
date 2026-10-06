#!/usr/bin/env python3
"""Bounded, deterministic queries against a raw declared-call graph. No runtime inference."""
import argparse
from collections import Counter, defaultdict, deque
import hashlib
import json
from pathlib import Path


class Graph:
    def __init__(self, raw):
        self.raw = raw
        self.methods = {m['id']: m for m in raw['definedMethods']}
        self.types = {t['name']: t for t in raw.get('types', [])}
        self.outgoing, self.incoming = defaultdict(list), defaultdict(list)
        self.nodes = set(self.methods) | set(raw.get('externalMethods', []))
        for edge in raw['calls']:
            self.outgoing[edge['from']].append(edge)
            self.incoming[edge['to']].append(edge)
            self.nodes.update((edge['from'], edge['to']))
        for mapping in (self.outgoing, self.incoming):
            for edges in mapping.values():
                edges.sort(key=lambda e: (e['from'], e['to'], e['opcode'], e.get('line', -1)))

    def require(self, method):
        if method not in self.nodes:
            raise ValueError('Unknown method ID; use search-methods first: ' + method)

    def describe(self, method):
        self.require(method)
        value = dict(self.methods.get(method, {'id': method}))
        value['defined'] = method in self.methods
        value['sourceFile'] = self.types.get(value.get('owner'), {}).get('sourceFile')
        value['incomingCallSites'] = len(self.incoming[method])
        value['outgoingCallSites'] = len(self.outgoing[method])
        return value

    def neighborhood(self, method, direction, depth, limit):
        self.require(method)
        mapping = self.incoming if direction == 'callers' else self.outgoing
        endpoint = 'from' if direction == 'callers' else 'to'
        seen, queue, edges = {method}, deque([(method, 0)]), []
        truncated = False
        while queue:
            current, distance = queue.popleft()
            if distance == depth:
                truncated |= bool(mapping[current])
                continue
            for edge in mapping[current]:
                if len(edges) == limit:
                    return {'edges': edges, 'truncated': True, 'depth': depth}
                edges.append(edge)
                other = edge[endpoint]
                if other not in seen:
                    seen.add(other)
                    queue.append((other, distance + 1))
        return {'edges': edges, 'truncated': truncated, 'depth': depth}

    def path(self, source, target, budget):
        self.require(source)
        self.require(target)
        previous, queue = {source: None}, deque([source])
        while queue:
            current = queue.popleft()
            if current == target:
                edges = []
                while previous[current] is not None:
                    edge = previous[current]
                    edges.append(edge)
                    current = edge['from']
                return {'status': 'STATIC_CANDIDATE_PATH', 'edges': list(reversed(edges))}
            for edge in self.outgoing[current]:
                other = edge['to']
                if other in previous:
                    continue
                if len(previous) >= budget:
                    return {'status': 'SEARCH_BUDGET_EXHAUSTED', 'visited': len(previous)}
                previous[other] = edge
                queue.append(other)
        return {'status': 'NO_DECLARED_PATH', 'visited': len(previous),
                'note': 'Dynamic dispatch, callbacks or reflection may require semantic edges.'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('graph', type=Path)
    commands = parser.add_subparsers(dest='command', required=True)
    for name in ('summary', 'public-apis', 'search-methods', 'describe-method', 'callers', 'callees', 'find-path'):
        sub = commands.add_parser(name)
        sub.add_argument('--limit', type=int, default=30)
        if name == 'search-methods':
            sub.add_argument('query')
        if name in ('describe-method', 'callers', 'callees'):
            sub.add_argument('method')
        if name in ('callers', 'callees'):
            sub.add_argument('--depth', type=int, default=1)
        if name == 'find-path':
            sub.add_argument('source')
            sub.add_argument('target')
            sub.add_argument('--max-nodes', type=int, default=10000)
    args = parser.parse_args()
    if not 1 <= args.limit <= 1000 or not 1 <= getattr(args, 'depth', 1) <= 5 or not 1 <= getattr(args, 'max_nodes', 1) <= 100000:
        parser.error('limit must be 1..1000, depth 1..5, max-nodes 1..100000')
    payload = args.graph.read_bytes()
    graph = Graph(json.loads(payload))
    try:
        if args.command == 'summary':
            packages = Counter(m['owner'].rsplit('/', 1)[0] for m in graph.methods.values())
            result = {'definedMethods': len(graph.methods), 'callSites': len(graph.raw['calls']),
                      'dynamicCallSites': len(graph.raw.get('dynamicCallSites', [])),
                      'packages': packages.most_common(args.limit), 'truncated': len(packages) > args.limit}
        elif args.command in ('public-apis', 'search-methods'):
            matches = sorted(m['id'] for m in graph.methods.values() if
                (args.command == 'public-apis' and m.get('access', 0) & 1 and
                 graph.types.get(m['owner'], {}).get('access', 0) & 1) or
                (args.command == 'search-methods' and args.query.lower() in m['id'].lower()))
            result = {'totalMatches': len(matches), 'truncated': len(matches) > args.limit,
                      'methods': [graph.describe(m) for m in matches[:args.limit]]}
        elif args.command == 'describe-method':
            dynamic = [s for s in graph.raw.get('dynamicCallSites', []) if s['caller'] == args.method]
            result = {'method': graph.describe(args.method), 'dynamicCallSites': dynamic[:args.limit],
                      'truncated': len(dynamic) > args.limit}
        elif args.command in ('callers', 'callees'):
            result = graph.neighborhood(args.method, args.command, args.depth, args.limit)
        else:
            result = graph.path(args.source, args.target, args.max_nodes)
            if 'edges' in result:
                result['totalEdges'] = len(result['edges'])
                result['truncated'] = len(result['edges']) > args.limit
                result['edges'] = result['edges'][:args.limit]
    except ValueError as error:
        parser.error(str(error))
    print(json.dumps({'basis': 'static', 'graphSha256': hashlib.sha256(payload).hexdigest(),
                      'artifacts': graph.raw.get('artifacts', []), 'result': result}, indent=2, sort_keys=True))


if __name__ == '__main__':
    main()
