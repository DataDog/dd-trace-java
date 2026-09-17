# GitHub Reviews

Read this reference before drafting or changing GitHub review content.

## GitHub suggestion mode

External review mutations require explicit user authorization. Otherwise, return
copy-ready suggestion blocks without posting them.

When authorized to add suggestions to a pending review:

- Resolve the authenticated user's existing `PENDING` review and confirm its commit
  matches the current PR head. Inspect all existing review threads as well as the
  pending comments before adding anything.
- If no matching authenticated-user `PENDING` review exists, or its commit does not
  equal the current PR head, stop without creating, replacing, rebasing, or mutating
  a review and report the mismatch. Those operations require separate authorization.
- Add a new draft comment only through an operation that explicitly attaches it to
  the resolved `PENDING` review ID. Never fall back to a standalone review-comment or
  reply endpoint. If the available API cannot attach the comment to that review,
  stop and return the copy-ready suggestion without posting.
- If a published conversation already covers the same path and line range and
  concerns Javadoc or comment wording, do not start another conversation. Surface it
  and skip the duplicate. Do not reply under pending-review authorization: publishing
  a reply requires separate explicit authorization. Only refine a comment already
  owned by the authenticated user's matching `PENDING` review.
- Prefix every GitHub comment created or edited by this skill, including a copy-ready
  comment returned without posting, with the exact Conventional Comments label
  `**suggestion:** `. Put the one-sentence reason immediately after the prefix, then
  add a blank line before the suggestion block. Do not vary the label or casing.
- Put the exact replacement in a GitHub `suggestion` code block. Keep the prose
  outside the block to one short reason for the rewrite.
- Anchor a multi-line suggestion within one diff hunk. If a rewrite crosses hunk
  boundaries, split it into coherent suggestions or preserve the unchanged trailing
  lines.
- Refine an existing draft comment in place instead of adding a duplicate. Pending
  comments may return `404` through the individual REST endpoint; resolve their node
  IDs through the pending review and use the review-comment update API when needed.
- Never submit the review while adding or editing comments. After suggestion
  mutations, verify the PR head is unchanged and the review still reports
  `state: PENDING` with no submission timestamp.

Review submission is a separate mode. Require the user to choose `COMMENT`,
`APPROVE`, or `REQUEST_CHANGES`; ask if the event is unspecified. Immediately before
submission, re-resolve the authenticated user's `PENDING` review, re-fetch its body
and comments, and confirm its commit matches the current PR head. Stop and report any
mismatch or concurrent content change. Submit the existing body unchanged unless
editing it was separately authorized. After submission, verify and report the
selected terminal state and submission timestamp.

Do not commit, push, create a pull request, publish a reply, or submit a review unless
the user separately authorizes the corresponding action.
