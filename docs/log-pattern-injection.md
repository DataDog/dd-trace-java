# Correlation fields in plain-text logs

Set `DD_LOGS_PATTERN_INJECT=true` (or `-Ddd.logs.pattern.inject=true`) to add
missing `dd.service`, `dd.trace_id`, and `dd.span_id` MDC converters to Logback
classic and Log4j2 PatternLayout patterns. The option is disabled by default.

For example:

```text
%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level %logger - %msg%n
```

becomes:

```text
%d{yyyy-MM-dd HH:mm:ss.SSS} dd.service=%X{dd.service} dd.trace_id=%X{dd.trace_id} dd.span_id=%X{dd.span_id} %-5level %logger - %msg%n
```

The first complete field, including date options and presentation wrappers,
remains at the beginning of the line. Existing MDC converters are retained;
restarting a layout does not add the same converters again. A converter that
prints the whole MDC already includes all correlation fields.

This option changes the text pattern only. `DD_LOGS_INJECTION` controls the
correlation values supplied to logging events. Existing application MDC values
are preserved. With log injection enabled, service remains available outside a
trace, while trace and span IDs are empty when no span is active.

Patterns are enhanced during layout initialization, not on each log event.
JSON layouts, Logback access layouts, and custom Log4j2 PatternSelector branches
are outside the scope of this option. Headers and footers are not enhanced.
