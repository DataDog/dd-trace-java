# Vert.x Web AppSec Instrumentation

Extended reference: [docs/appsec/ig-events.md](../../../../docs/appsec/ig-events.md),
[docs/appsec/blocking-patterns.md](../../../../docs/appsec/blocking-patterns.md)

## Advice classes are inlined, not injected

`RoutingContextImplInstrumentation` has no `helperClassNames()` override -- it uses the empty
default. Advice classes (`RoutingContextJsonAdvice`, `RoutingContextFilenamesAdvice`, etc.) are
inlined by ByteBuddy, not injected as helpers. Only runtime-instantiated handlers go in
`helperClassNames()` (e.g. `WafPublishingBodyHandler`).

New `@RequiresRequestContext` classes for `RoutingContextImplInstrumentation` must NOT be added
to `helperClassNames()`.

## `fileUploads()` requires prior `BodyHandler` execution

`RoutingContext.fileUploads()` returns an empty set unless a `BodyHandler` has previously parsed
the body. `setExpectMultipart(true)` + `endHandler` populates `formAttributes()` but NOT file
uploads.

## Use distinct `CallDepthThreadLocalMap` keys per advice class

Each advice class in `RoutingContextImplInstrumentation` must use a distinct key in
`CallDepthThreadLocalMap` to avoid re-entrancy interference. Using the same key across the
filenames advice and the JSON advice causes one to suppress the other.

## No `effectivelyBlocked()` in Vert.x advice

Do not call `effectivelyBlocked()` in Vert.x advice. See
[docs/appsec/blocking-patterns.md](../../../../docs/appsec/blocking-patterns.md#vertx).
