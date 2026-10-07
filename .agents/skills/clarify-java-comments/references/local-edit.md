# Local Edits

Read this reference before changing Java documentation in the local checkout.

## Local edit mode

Edit only after an explicit request to change documentation. In PR context, ask
whether the user wants a local edit or a GitHub suggestion when unclear. Otherwise,
remain read-only and return copy-ready replacements.

- Resolve a file directly. Resolve a class or member to its source declaration. Ask
  before expanding an ambiguous, inherited, generated, or shared target.
- File scope includes its Javadocs and explanatory comments. Class scope includes
  the class and direct members; class-Javadoc scope includes only the attached type
  comment. Member scope includes its Javadoc and comments within its declaration or
  body. Exclude nested, local, and anonymous types unless explicitly requested.
- Change only documentation and preserve the comment kind unless requested. Do not
  normalize content outside the authorized comment spans.
- Verify the final diff changes only authorized comments, then apply the shared
  [replacement checks](../SKILL.md#check-the-replacement).
