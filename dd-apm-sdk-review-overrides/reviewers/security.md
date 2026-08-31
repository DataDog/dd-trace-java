Override for `reviewers/security.md` (in the core skill folder) — read that file first, then this.

# Security — dd-trace-java specifics

This file starts with one confirmed pattern and should grow as more findings are reviewed — do not treat it as exhaustive.

## "Set it before checking it" — a security control that silently does nothing

**The pattern:** code turns on a powerful, process-wide capability (a JVM crash handler, a loaded native library, an active instrumentation hook), and only afterwards checks whether the target of that capability is safe to trust. If the check fails, the capability should turn back off — but usually it doesn't, because the code only reacts to a failed check by skipping some *later*, unrelated step.

In pseudocode:

```
setHandler(path)              // capability is live now
if (!isTrusted(path)) {
  return                      // too late — setHandler already ran
}
writeConfig(path)
```

The fix just swaps the order:

```
if (isTrusted(path)) {
  setHandler(path)
  writeConfig(path)
}
```

**Why it matters:** this is not a race condition — one thread, no timing needed, nothing concurrent. It's a plain bug: the trust check exists and runs, but by the time it fails it can no longer stop anything. Treat it as **P0**, not a P1 ordering nit, whenever the bypassed check was the only thing standing between an untrusted path and code/script execution.

**Where to look for it in this codebase:**
- Anything that arms a crash/error handler before validating the script or path it points to.
- Code that reuses a pre-existing directory or file through the shared `TempLocationManager`.
- A native library load (`System.load`/`loadLibrary`) whose path comes from config.
- A remote-config value applied to a live component before it's schema/bounds-checked.
- An instrumentation hook that activates before its own safety gate — if that gate is Muzzle, check `dd-apm-sdk-review-overrides/reviewers/design.md` first so you don't report the same thing twice under two lenses.

## Do not

- Don't call this a design or coherence issue — it's a security control that runs but has no effect.
- Don't flag ordinary validate-then-use code just because the check and the use live in different methods or classes. The bug is a missing link between the check's *result* and the action — confirm that link is actually broken before reporting.
