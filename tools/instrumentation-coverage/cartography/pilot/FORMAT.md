# Behavioral assessment format, version 2

`evidence_model.py` is the executable domain validator. JSON field definitions below are the schema;
this workflow does not claim full JSON Schema validation or automatic semantic approval.

| Object | Required fields and meaning |
|---|---|
| Assessment | `schemaVersion: 2`, `library`, `version`, `sourceVersion`, `inputIdentity`, `capability`, `reviewStatus`, `sources`, `claims`, `variants`, `limitations` |
| Input identity | Canonical JSON SHA-256 values `report` and `namingEvidence`; emitted by prepare |
| Capability | Stable `id`, readable `name` |
| Source | Unique `id`, `origin` (`local` or `upstream`), `path`, `sha256`, `ranges` (inclusive one-based line pairs) |
| Claim | Unique `id`, `kind` (`trigger`, `outcome`, `assertion`, `scope`), `text`, `scope`, nonempty `citations` |
| Citation | `sourceId`, `startLine`, `endLine`, exact nonempty `quote` within those lines |
| Variant | Unique `id`, `name`, `trigger`, `expected`, `triggerClaims`, `outcomeClaims`, `dimensions`, `upstream`, `referenceScopes`, `testBindings`, `assessmentSummary`, `limits`; optional `helpers` |
| Test binding | Exact recorded `testId`, `dimension`, `evidenceStatus`, `claimIds`, `reason` |
| Helper | `sourceId`, `startLine`, `endLine`, `name` |

Claim scope is one of `component`, `mocked-lifecycle`, `http-integration`, `source-only`. A reference's
`referenceScopes` description qualifies each upstream example individually. Variant `upstream` entries
are exact example names in the packet, including class and method. No binding to a cluster implies
that all its tests establish the same behavior.

An assessed test binding uses `BEHAVIOR_SUPPORTED` or `PARTIALLY_SUPPORTED` and must reference at least
one assertion claim citing local source. A test outcome (passed/failed/skipped) is retained separately;
it does not automatically confer support. The author must justify the association and its scope.
Unassessed dimensions remain `NOT_ASSESSED`. Statistical candidates are added separately by the builder
as `SIMILAR_EXECUTION_ONLY`; they cannot promote a variant to behavioral support.

Review status is `LLM_DRAFT_NOT_HUMAN_REVIEWED` or `HUMAN_REVIEWED`; the latter requires a `reviewRecord`.
Only a human review action should populate it. Reference validation is not that action.

Stable variant IDs belong to behavior descriptions, not display names or current test counts. Full
catalog completeness, cross-version identity reconciliation and causal execution graphs are not
provided by this schema.
