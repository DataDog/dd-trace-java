"""Structural stage contracts; source-grounded meaning still requires author review."""

import re


def meaningful_label(value):
    return (isinstance(value, str) and bool(value.strip()) and
            not re.fullmatch(r'(?:step|stage|checkpoint|method|group)[\s_-]*\d*|todo|tbd|unknown',
                             value.strip(), re.IGNORECASE))


def validate_stages(flow, source_ids):
    presentation = flow.get('stagePresentation', {'mode': 'semantic'})
    if not isinstance(presentation, dict) or presentation.get('mode') not in ('semantic', 'ungrouped'):
        raise ValueError(flow['id'] + ': invalid stage presentation')
    if presentation['mode'] == 'ungrouped':
        reason = presentation.get('reason')
        if not isinstance(reason, str) or not reason.strip():
            raise ValueError(flow['id'] + ': ungrouped stages need an explicit reason')
        return
    for step in flow['steps']:
        prefix = flow['id'] + '/' + step['id']
        if not meaningful_label(step.get('label')):
            raise ValueError(prefix + ': meaningful stage label required (not a placeholder)')
        if not isinstance(step.get('rationale'), str) or not step['rationale'].strip():
            raise ValueError(prefix + ': stage needs a source-grounded lifecycle rationale')
        refs = step.get('sourceIds')
        if not isinstance(refs, list) or not refs or any(not isinstance(s, str) for s in refs) or set(refs) - source_ids:
            raise ValueError(prefix + ': stage needs known source references')
