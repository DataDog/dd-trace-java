# Lib-injection Smoke Tests

## Goals

Test lib-injection (aka single step instrumentation) safeguards.

## Tests

### Multiple JVM agents

Check the Java tracer agent is not bootstrap if multiple JVM agent are detected.
This only applies when using lib-injection and can be avoided by forcing injection.
