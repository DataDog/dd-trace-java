# Jetty AppSec Instrumentation

Extended reference: [docs/appsec/blocking-patterns.md](../../../../docs/appsec/blocking-patterns.md)

## Blocking: Jetty uses Servlet container model

Jetty follows the Servlet blocking pattern. `effectivelyBlocked()` must be called explicitly
after creating `BlockingException`, inside `if (brf != null)`. See
[docs/appsec/blocking-patterns.md](../../../../docs/appsec/blocking-patterns.md#servlet-containers-tomcat-jersey-resteasy).
