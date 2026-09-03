# Javadoc Tags

Read this reference for whole-comment Javadoc rewrites or explicit tag repairs.

## Repair Javadoc tags as a bonus pass

When rewriting a Javadoc, also fix missing, stale, malformed, or misused tags in
that same comment when the source makes the intended contract clear. Preserve the
project's local ordering and style, and do not invent guarantees merely to fill a
tag.

- Use `@param` for each documented type parameter, record component, and method or
  constructor parameter. Describe its role, constraints, or special values instead
  of repeating its name or type.
- Use `@return` for the result contract, including meaningful `null`, empty, cached,
  or sentinel behavior. Do not hide the return contract in the opening prose.
- Use `@throws` only for exceptions the implementation or contract can actually
  expose, and state the condition that triggers each one. Remove stale exception
  tags and avoid cataloguing incidental unchecked exceptions.
- Use `@see` for genuinely useful related API. Use an inline `{@link Type#member}`
  instead when the reference belongs naturally in a sentence.
- Use `{@code ...}` for identifiers, literals, expressions, and short code fragments
  that should render verbatim. Prefer it to raw `<code>` markup and unnecessary
  quotation marks.
- Use `{@link Type#member}` when navigation adds value; add a label only when it
  reads better in context. Verify that the target and member syntax resolve, and
  do not turn every type or method name into a link.
- Keep all tags aligned with the current signature: add missing parameter tags,
  remove renamed or deleted parameters, and preserve declaration order. Retain
  other valid tags such as `@since`, `@deprecated`, and `@implNote` unless the
  requested rewrite makes a source-backed correction necessary.
