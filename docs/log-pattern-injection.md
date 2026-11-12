# Correlation fields in plain-text logs

Set `DD_LOGS_PATTERN_INJECT=true` (or `-Ddd.logs.pattern.inject=true`) to add
missing `dd.service`, `dd.trace_id`, `dd.span_id`, `dd.env`, and `dd.version`
MDC converters to Logback classic and Log4j2 PatternLayout patterns. The option
is disabled by default.

For example:

```text
%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level %logger - %msg%n
```

becomes:

```text
%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level %logger - dd.service=%X{dd.service} dd.trace_id=%X{dd.trace_id:-0} dd.span_id=%X{dd.span_id:-0} dd.env=%X{dd.env} dd.version=%X{dd.version} %msg%n
```

Fields are inserted immediately before the message converter (`%m`, `%msg`, or
`%message`), preserving the existing timestamp, level, logger, and separators.
Message-only wrappers stay around the message. Color wrappers containing a prefix
keep that prefix before the new fields. If a transforming wrapper (such as replace,
encoding, truncation, or a formatted group) contains both a prefix and message, the
fields are added after the complete wrapper, preserving its original output and
keeping correlation IDs outside the transformation. Such a wrapper containing an
internal newline is left unchanged, because adding fields outside it could create
a separate log line. Without a message converter, fields are added
before the first newline converter or literal line break, or at the end when no
line break is present. Existing MDC converters are retained;
restarting a layout does not add the same converters again. A converter that
prints the whole MDC already includes all correlation fields.

This option changes the text pattern only. `DD_LOGS_INJECTION` controls the
correlation values supplied to logging events and defaults to `true`. This option
does not override an explicit `DD_LOGS_INJECTION=false`; the added ID converters
then fall back to `0` unless the application supplies its own MDC values.
Existing application MDC values are preserved. With log injection enabled,
service remains available outside a trace. Newly added trace and span converters render `0` when no span is active;
undefined environment and version values remain empty. Existing converters,
including their default values, are retained. The example above uses Logback
syntax; Log4j2 uses `%equals{%X{dd.trace_id}}{}{0}` and the corresponding span
converter for the same fallback. Releases before Log4j2 2.5 use an
agent-provided ID converter to avoid nested replacement parser bugs in composite
patterns. It reads the event MDC and uses the same zero fallback.

Patterns are enhanced during layout initialization, not on each log event.
JSON layouts, Logback access layouts, and custom Log4j2 PatternSelector branches
are outside the scope of this option. Headers and footers are not enhanced.
