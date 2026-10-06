"""Validate and project authored catalog grouping; never infer it from scenario names."""

import re
from stage_contract import meaningful_label as stage_label


def meaningful_label(value):
    return stage_label(value) and not re.fullmatch(
        r'(?:family|dimension|value|scenario)[\s_-]*\d*', value.strip(), re.IGNORECASE)


def project(families, behavior_ids):
    navigation = {'schemaVersion': 1, 'families': []}
    owners = set()
    for family in families:
        if family['classification'] == 'excluded':
            continue
        members = (family.get('flowIds', []) if family['classification'] == 'mapped'
                   else ['candidate.' + family['id']])
        authored = family.get('navigation')
        if not meaningful_label(family.get('name')) or not isinstance(authored, dict):
            raise ValueError(family['id'] + ': authored catalog navigation required')
        if not isinstance(authored.get('description'), str) or not authored['description'].strip():
            raise ValueError(family['id'] + ': navigation needs a description')
        dimensions = authored.get('dimensions')
        if not isinstance(dimensions, list):
            raise ValueError(family['id'] + ': declare dimensions, or [] for direct scenario browsing')
        allowed = {}
        for dimension in dimensions:
            key = dimension.get('id')
            if not isinstance(key, str) or not key.strip() or key in allowed or not meaningful_label(dimension.get('label')):
                raise ValueError(family['id'] + ': invalid or duplicate navigation dimension')
            values = dimension.get('values', [])
            ids = [v.get('id') for v in values]
            if not ids or any(not isinstance(i, str) or not i.strip() for i in ids) or len(set(ids)) != len(ids) or any(not meaningful_label(v.get('label')) for v in values):
                raise ValueError(family['id'] + ': dimension needs unique named values')
            allowed[key] = set(ids)
        scenarios = authored.get('scenarios')
        if not isinstance(scenarios, dict) or not scenarios or set(scenarios) != set(members) or len(set(members)) != len(members):
            raise ValueError(family['id'] + ': navigation must retain exactly the family scenarios')
        labels = set()
        for flow_id, scenario in scenarios.items():
            if flow_id not in behavior_ids or flow_id in owners:
                raise ValueError(flow_id + ': absent or ambiguous catalog family membership')
            owners.add(flow_id)
            values = scenario.get('values')
            if not meaningful_label(scenario.get('label')) or not isinstance(values, dict) or set(values) != set(allowed):
                raise ValueError(flow_id + ': scenario needs a label and exactly its family dimensions')
            if any(not isinstance(value, str) or value not in allowed[key] for key, value in values.items()):
                raise ValueError(flow_id + ': unknown navigation dimension value')
            identity = (tuple(values[key] for key in allowed), scenario['label'].strip().casefold())
            if identity in labels:
                raise ValueError(flow_id + ': indistinguishable scenario labels within selected dimensions')
            labels.add(identity)
        navigation['families'].append(dict(id=family['id'], name=family['name'], **authored))
    if owners != set(behavior_ids):
        raise ValueError('Catalog navigation has orphan scenarios: ' + ', '.join(sorted(set(behavior_ids) - owners)))
    return navigation


def validate_report(navigation, behavior_ids):
    if not isinstance(navigation, dict) or navigation.get('schemaVersion') != 1:
        raise ValueError('Unsupported catalog navigation schema')
    families = navigation.get('families')
    if not isinstance(families, list) or not families:
        raise ValueError('Catalog navigation needs families')
    ids = [f.get('id') for f in families]
    if any(not isinstance(i, str) or not i.strip() for i in ids) or len(set(ids)) != len(ids):
        raise ValueError('Catalog navigation needs unique family IDs')
    project([dict(id=f['id'], name=f['name'], classification='mapped',
                  flowIds=list(f.get('scenarios', {})), navigation={k: f[k] for k in ('description', 'dimensions', 'scenarios')})
             for f in families], set(behavior_ids))
