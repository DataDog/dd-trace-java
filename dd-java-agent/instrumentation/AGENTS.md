# Instrumentation — Cross-Cutting Patterns

Extended reference: [docs/instrumentation/advice-patterns.md](../../docs/instrumentation/advice-patterns.md)

## `helperClassNames()` — list ALL nested inner classes

ByteBuddy injects exactly the listed classes. It does NOT auto-discover `$Inner` classes.
Any nested class used at runtime must be listed explicitly. Silent failure: `NoClassDefFoundError`
swallowed by `suppress = Throwable.class` -- data silently discarded.

## `muzzleDirective()` must never return null in modules with `assertInverse = true`

A null return causes the new instrumentation to be included in every muzzle test in the module,
including inverse-mode tests where it is expected to fail. CI fails with
"MUZZLE PASSED BUT FAILURE WAS EXPECTED".

Check the module's `build.gradle` for `assertInverse = true` before adding a new instrumentation.
If present, override `muzzleDirective()` with a non-null value matching the new muzzle block.

## `@Advice.FieldValue` -- use the most specific stable-descriptor type

Never use `Object`. Type to the field's declared type or to an interface with stable descriptors
across all muzzle-tested versions. Correct typing gives muzzle validation for free.

## Business logic belongs in helper classes, not in advice methods

Advice methods should contain only null/context guards and a single call to a helper or module.
Any branching, data transformation, or object construction must move to a `*Helper` class and
be covered by unit tests directly on the helper.

## `@RequiresRequestContext` + `Config.get()` = muzzle failure

Do not initialize `static final` fields from `Config.get()` in any `@RequiresRequestContext`
advice inner class. Move such constants to the helper class in `helperClassNames()`.
See [docs/instrumentation/advice-patterns.md](../../docs/instrumentation/advice-patterns.md#constants-from-configget-must-not-be-in-requiresrequestcontext-advice-inner-classes).

## `suppress = Throwable.class` does not protect loops inside helpers

An uncaught exception inside a `for` loop in a helper short-circuits remaining iterations.
Each per-item operation that can fail independently must be wrapped in its own `try/catch`.
