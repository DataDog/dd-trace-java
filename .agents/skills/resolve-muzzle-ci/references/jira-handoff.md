# Jira handoff for a new-version support gap

Create a ticket only when all of these are true:

- the failing artifact is a newly published version of the target library covered by a muzzle pass
  range;
- artifact resolution completed and muzzle reported a real compatibility mismatch for that version;
- the repository change leaves the new version unsupported, for example by capping the existing
  module below it; and
- follow-up work is needed from the owning integration team to restore support.

Do not create a ticket for transient MagicMirror, Depot, Maven, DNS, TLS, timeout, 429, or 5xx
failures. Also do not create one for defective publications, missing or irrelevant transitive
artifacts, tooling/JDK failures, stale inverse assertions, or an instrumentation change that already
restores and verifies the new version.

## Destination and authorization

First confirm that the user authorized Jira creation. If not, ask whether they want the follow-up
created. When they do, use the Jira project/board they supplied or established repository/team
context. If it is unknown, ask: `Which Jira project or board should receive the muzzle follow-up?`
Continue safe local work while waiting, but do not guess a project key.

Before creating a ticket, check supplied issue links and search the selected project for the same
integration, artifact, and unsupported version or version line. Reuse a ticket that already covers
restoring this support and report its key; do not create a duplicate. Updating an existing ticket
requires authorization for that update. If search is unavailable, report that duplicate detection
could not be completed and provide a draft rather than claiming no matching issue exists.

Use `.github/CODEOWNERS` and nearby ownership metadata to suggest the related team. If ownership is
ambiguous, leave assignment open and state the candidate teams rather than guessing an assignee.

Creating the ticket is an external write. Do it only when the user's request includes ticket
creation and an available Jira integration is authorized. If no Jira integration is available,
produce the complete copy-ready ticket below and ask the user to connect Jira; do not claim creation.

## Ticket content

Use a concise summary such as:

```text
Restore <library> <version> support in <instrumentation module>
```

Include:

- affected integration/module and owning team, if known;
- failing CI URL, Gradle task, commit/PR, JVM, artifact coordinates, and first failing version;
- the last supported version and the newly published unsupported version;
- the relevant muzzle failure log as an attachment when possible, otherwise a focused fenced excerpt
  containing the task, coordinates, first causal exception, and complete mismatch list;
- analysis showing the changed or missing API/linkage, why this is a compatibility regression rather
  than a transient repository/platform failure, and why support was not restored in the current change;
- the exact range cap or other containment applied and the resulting support gap;
- local validation commands and results;
- requested work to restore support, suggested team, removal condition, and upstream release notes or
  issue when applicable.

Do not copy credentials, signed artifact URLs, access tokens, unrelated environment output, or the
entire noisy CI log into the description. Preserve the full relevant log as a safe attachment or
artifact when available.

After creation, report the Jira key and link. Add the key to the build-file comment only if doing so
matches nearby repository convention; the comment must remain understandable without Jira access.
