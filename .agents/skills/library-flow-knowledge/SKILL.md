---
name: library-flow-knowledge
description: Build a versioned library behavior catalog from documentation, source and upstream tests; group flows into families and record upstream execution fingerprints with meaningful stages.
---

# Library flow knowledge

**Static graph + docs + upstream tests → behavior catalog and families → upstream recordings → stages.**

Resolve the library version and artifacts. Generate or reuse its static method graph.
Use documentation, implementation and upstream tests to name scenarios and explain their inputs
and outcomes. Group them into useful functionality families with explicit navigation dimensions.
Split scenarios for meaningful behavior differences, not automatically for each test or overload.

Record the selected upstream tests with a lightweight method-entry agent independent of tracer
Context. Preserve exact invocation IDs, method counts, available call edges and async handoffs.
Keep recorder-off outcomes and repeated recordings so runtime alternatives remain available.

Combine the recordings with the static graph to assign meaningful lifecycle stage names.
Never manufacture Step 0/Step 1 labels. Keep each upstream example/repetition separate.
If stages add no meaning, use an explicitly ungrouped method view.

Keep library-specific choices as catalog data, not library-specific workflow scripts.
Preserve the authored families and stages in the report. Documentation-only scenarios without a
recorded fingerprint remain in the catalog but cannot be classified until a reference is recorded.

Read [REFERENCE_CARTOGRAPHY.md](../../../tools/instrumentation-coverage/REFERENCE_CARTOGRAPHY.md)
for recording and fingerprint comparison. Read [AUTHORING.md](../../../tools/instrumentation-coverage/AUTHORING.md)
and [KNOWLEDGE_FORMAT.md](../../../tools/instrumentation-coverage/KNOWLEDGE_FORMAT.md) only when
using the legacy module KB schema.

Hand the catalog, graph and recordings to instrumentation-quality. Local test classification is
automatic: do not require a separate manual assertion review or invent verification statuses.
