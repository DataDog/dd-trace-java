# Regenerating names from runtime families

This experiment separates reproducible evidence extraction from LLM interpretation. It does not
consult the old flow catalog for names, grouping or corridor construction. The saved agent run's
knowledge files are still hash-checked as part of evidence integrity, but are not read for naming.

## Inputs and outputs

- `semantics.py` builds `build/naming-evidence.json` from the statistically discovered families,
  pinned upstream test sources, recorded method support and synchronous nesting. Full test classes
  are included so helper methods and fixture setup can be inspected, not guessed from test names.
- `spring-6.0.2-interpretation.json` is an **LLM-authored draft** produced in this experiment. It
  contains 53 names, feature groups, explanations, evidence-test references and landmark rules.
  It is not automatically inferred by Python and has not been human-reviewed.
- `classify.py` validates that interpretation against the packet and builds the report. It preserves
  the original statistical matching and measured Context observations. Labels do not affect scores.
- `build/report/naming-evidence.json` is the exact naming input for a rendered report. Report provenance
  stores packet, interpretation and generator hashes. Source excerpts are available in the UI.

## Workflow

1. Run collection/clustering as documented in README.md. The classifier writes its naming packet
   before reading the interpretation. If the interpretation is missing or stale it stops rather than
   silently applying old names. Any existing HTML is still the previous report; a failed command does
   not update it.
2. Give an LLM the new packet, this instruction file and the annotation schema/example. Ask it to
   inspect the test bodies, assertions and relevant helpers, then author a new interpretation.
3. Validate the output using `semantics.py --interpretation PATH` for the saved classification, or
   rerun `classify.py --interpretation PATH` for newly clustered data. The latter checks the exact new
   packet and rebuilds the report in one step.
4. Review the source-linked names. Rendering is deterministic with a fixed interpretation; asking an
   LLM to rewrite the interpretation is not deterministic. Record review separately from validation.

The normal `run.py` pipeline uses the saved interpretation. Changed source/fingerprint evidence
requires the explicit naming step again. There is no unattended external LLM API call.

## Instructions for the interpreting LLM

Use only the evidence packet and explicitly supplied sources. Do not import names or paths from an
older catalog. For every family produce `id`, `feature`, `name`, `summary` and `evidenceTests` (all member
names). Keep the exact family id; display names are not identity. Include the packet SHA-256.

- Describe what member bodies actually exercise. Test names are hints, not claims. Inspect shared
  helpers from `sources` when a test delegates its assertions.
- Prefer a short behavioral title. Distinguish type-support checks, input rejection, lifecycle
  callbacks, response conversion and end-to-end execution.
- If a family mixes success/error or multiple APIs, name the shared behavior and explain the variants.
  Do not pick one example and imply that its exact scenario describes every family member.
- Cite every member source through `evidenceTests`. Source links and excerpts are deterministic.
  If evidence is inadequate, say so in the name/summary instead of inventing semantics.
- Define `stageRules` as ordered objects with `id`, `label`, and regex `pattern` over recorded method
  identities. First match assigns a method. Include an explicit support-method fallback. These are
  conceptual landmark groups; do not imply temporal order, a required path, or causal async edges.
- Do not use Context colors or our test matches to decide the name. Do not interpret a similarity
  match as proof that our test satisfies the named behavior.
- Public documentation can supplement the source only when actually inspected and cited. This trial
  uses no external documentation.

The validator checks exact packet identity, one interpretation per family, complete member-test
references, required labels, unique stage IDs and complete recorded-method allocation. It does **not**
verify natural-language truth. Human review is still meaningful.

## Continuity and current limits

Family ids derive from upstream member-test identities, independently of labels. Names can change
without changing those ids. The packet hash invalidates descriptions when supporting data changes.
Automatic reconciliation of splits/merges is not implemented; changed membership produces different
ids that must be reviewed. This trial preserves the 53 original clusters rather than claiming that
one cluster is always one semantic scenario.

Small type-check families cannot satisfy the current four-shared-method matching threshold. Zero
candidate associations therefore does not by itself indicate a missing test. Likewise, a matched
failure-oriented reference is not proof that our test exercised that failure: inspect the source
and shared/absent methods. The inference remains a retrieval aid.
