# Polymorphic dispatch

Checks #5, J2.

## #5 Polymorphic dispatch on a hot path

- **Flag:** a change that makes a hot call site see more receiver types, enough to stop the JIT
  from inlining it.
- **Not when:** the site stays at one or two types, or one type dominates.
- **Confidence / level:** flag-as-measure ("may defeat inlining; verify on the target runtime").
  P3.
- **Fix:** keep hot call sites to one or two receiver types, or specialize the code.

## J2 Megamorphic dispatch: parked

**Do not raise megamorphism findings in review.** The mechanism and the audit plan are in
`../roadmap.md`. They are reference material for authors, not a review check.

One rule still applies when you write about dispatch: do not call a virtual call "slow". A
well-predicted indirect branch costs a couple of cycles. The real cost of a megamorphic call is
that the JIT cannot inline across it, so neither side is optimized for the other.
