# Feature Flags migration POC

This branch is an integration prototype, not a production release candidate.
It does not replace or rewrite PRs #12250, #12251, or #12252.

## Baseline

- Master: `7b903a53644abc39f55b2fb21283546ae9801f35`.
- Imported combined prototype: `f5a7e45801c0c43ea03fb0f56eda415615186a58`.
- Existing dogfood evidence: `JAVA_FEATURE_FLAGS_ADVERSARIAL_VALIDATION_20260918.md` in the parent workspace.
- Preserve current parser, privacy, transport, and dependency fixes.

## Execution checklist

- [x] Create an isolated branch from current master.
- [ ] Import the existing standalone and injection implementation.
- [ ] Extract shared evaluation, parsing, and configuration state into core.
- [ ] Separate direct HTTP, agent adapters, and the standalone distribution.
- [ ] Fix activation, global disable, shared-consumer lifecycle, and bridge compatibility.
- [ ] Use a normal OTel API dependency for the POC without installing an SDK or exporters.
- [ ] Preserve late application OTel SDK registration.
- [ ] Add stable span-enrichment naming and retain legacy aliases and bridge shims.
- [ ] Build both distributions from one revision and verify publication metadata.
- [ ] Run canonical, lifecycle, bridge, and injection tests.
- [ ] Run no-agent, OTel-only, injection, and RC dogfood cases against the same artifacts.
- [ ] Record actual SSI deployment and rollback separately from manual attachment.

## Non-negotiable behavior

Manual registration activates standalone delivery by default unless explicitly disabled.
Explicit RC never falls back to direct CDN polling.
RC and manual provider registration remain supported.
Closing one consumer must not stop another consumer's refresh.
An application-selected provider must not be replaced by injection.
Configuration and product events must work without an OTel collector.
Supported mixed installations must share compatible payloads and one runtime owner.

## Provisional decisions

The POC uses a non-shaded OTel API dependency. Java Language Tools must approve the release packaging contract.
Legacy setting names and bridge types remain compatibility shims. Their removal versions remain undecided.
Actual SSI certification needs a named deployment target. Manual `-javaagent` tests do not satisfy that requirement.
