# Transaction Tracking — extraction sources

The tracer reads an optional list of `*`-glob patterns from the existing
`APM_TRACING` remote-config product under the field `tt_extraction_patterns`.

When that list is non-empty, every server span gets a single tag
`_dd.tt.extraction_sources` whose value is a CSV of matching inbound HTTP
header names and query-string parameter names.

## Wire format

```json
{
  "lib_config": {
    "tt_extraction_patterns": ["x-trace-*", "tenant", "*-id"]
  }
}
```

- Patterns support only `*` (zero-or-more). Matching is case-insensitive on
  the candidate name. Values are never inspected.
- Empty or missing list disables the feature; the next request is back to
  a single volatile read + `isEmpty()` check (no allocation).

## Tag shape

- Tag key: `_dd.tt.extraction_sources` (constant
  `InstrumentationTags.TT_EXTRACTION_SOURCES`).
- Value: deterministic CSV. `header:<lowercased-name>` entries are emitted
  first in alphabetical order, followed by `qs:<lowercased-name>` entries
  in alphabetical order. Duplicates within a bucket are collapsed.
- The tag is set only when at least one match is found.

Example: with patterns `["x-trace-*", "tenant", "*-id"]` and an inbound
request bearing the headers `X-Trace-Id`, `X-Trace-Source`, `Authorization`
and the query string `?tenant=42&debug=1&request-id=abc`, the tag value is:

```
header:x-trace-id,header:x-trace-source,qs:request-id,qs:tenant
```

## Coverage

The feature is implemented at the `HttpServerDecorator` layer, with the
`forEachRequestHeaderName` extension point overridden in the `javax-servlet`
2.2 and 3.0 decorators. Stacks built on top of those (Spring WebMVC, the
typical Tomcat / Jetty servlet path) get the tag transparently. Stacks
whose decorator does not override `forEachRequestHeaderName` (Netty,
Vert.x, WebFlux, …) fall back to the no-op default and silently produce
no tag until someone wires the override for that stack.
