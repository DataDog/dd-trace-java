# Local Edits

Read this reference before changing Java documentation in the local checkout.

## Local edit mode

A target does not authorize mutation. Edit the checkout only after an explicit
affirmative request to change its documentation. Negated, hypothetical, quoted, or
suggestion-only wording is not authorization. In PR context, ask whether the user
wants a local edit or a GitHub suggestion when unclear. Otherwise, stay read-only
and return findings with copy-ready replacements.

- Resolve a file directly and a class or member from its qualified name, repository
  context, imports, and enclosing types. Targets are declaration-scoped. For an
  ambiguous, inherited, generated, or shared declaration, report the actual
  declaration and ask before expanding the edit to it.
- Before editing, resolve the target's canonical path and inspect symlinks. If the
  canonical target falls outside the explicitly named checkout or workspace, stop
  and ask for authorization for that actual target.
- For a file, edit only its Javadocs and explanatory comments. Class scope includes
  the class and direct members; class-Javadoc scope includes only the attached type
  comment. Member scope includes its Javadoc and explanatory comments inside its
  declaration or body. All scopes exclude nested, local, and anonymous types unless
  explicitly requested.
- Do not change executable code, declarations, annotations, string literals,
  executable test code, or unrelated documentation. Preserve comment form unless
  the user requests a Javadoc or comment-kind change. Do not normalize content
  outside the authorized comment spans.
- Compare the result with the pre-edit content and verify that only authorized
  comment spans changed. Run the narrowest practical validation in the shared
  [Check the replacement](../SKILL.md#check-the-replacement) guidance. Report any
  source claim corrected by the edit and any validation that could not run.
