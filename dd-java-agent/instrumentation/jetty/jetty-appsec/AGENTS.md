# Jetty AppSec Instrumentation

Extended reference: [docs/appsec/blocking-patterns.md](../../../../docs/appsec/blocking-patterns.md),
[docs/appsec/multipart-frameworks.md](../../../../docs/appsec/multipart-frameworks.md)

## Jetty 8.x: no `getSubmittedFileName()`, manual parsing required

`Part.getSubmittedFileName()` (Servlet 3.1) is not available in Jetty 8.x (Servlet 3.0). Filenames
must be extracted by manually parsing the `Content-Disposition` header from each `Part`.

Exit advice on `getParts()` is the correct instrumentation point for Jetty 8.x. There is no
`parseParts()` equivalent to intercept.

## Jetty 9.4/10: muzzle discriminator via `_dispatcherType`

The field `_dispatcherType: Ljavax/servlet/DispatcherType;` distinguishes Jetty 9.4/10.x
(javax namespace) from Jetty 11+ (jakarta namespace). Use this field as the muzzle reference
rather than trying to match on API version strings alone.

## Blocking: Jetty uses Servlet container model

Jetty follows the Servlet blocking pattern. `effectivelyBlocked()` must be called explicitly
after creating `BlockingException`, inside `if (brf != null)`. See
[docs/appsec/blocking-patterns.md](../../../../docs/appsec/blocking-patterns.md#servlet-containers-tomcat-jersey-resteasy).
