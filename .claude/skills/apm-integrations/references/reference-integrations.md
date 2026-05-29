# Reference Integrations

Canonical examples for each Datadog APM semantic category in dd-trace-java. **Read the reference integration for your category end-to-end before writing code** — the structure, decorator choice, and helper-class declaration patterns are consistent within a category, and the live source is the authoritative example.

## How to Use This Reference

1. Find your integration's semantic category in the table below.
2. Navigate to `dd-java-agent/instrumentation/{reference}/` in the dd-trace-java repository.
3. Read the InstrumenterModule, Advice classes, Decorator, and test files end-to-end.
4. Use the reference as a structural template for your new integration; only the type/method matchers and helper list change.

## By Semantic Category

Categories below match the `apm_semantic_conventions` taxonomy (`aws`, `cache`, `database`, `graphql`, `grpc-client`, `grpc-server`, `http-client`, `http-server`, `llm`, `messaging`, `search`).

| Category | Canonical Reference | Decorator Base Class | Notable Patterns |
|---|---|---|---|
| **aws** | `aws-java-sdk-2.2/` (also `aws-java-sdk-1.11/` for v1) | `AwsSdkClientDecorator` | Service + operation tagging from `Request`, peer service from endpoint, AWS SDK request handlers |
| **cache** | `jedis-1.4/`, `lettuce-5/` | `CacheDecorator` (or `BaseDecorator`) | Command name as resource, host/port for peer service, separate single-node vs cluster handling |
| **database** | `jdbc/` | `DatabaseClientDecorator` | DBM SQL comment injection, connection metadata extraction (host/port/db), Statement / PreparedStatement / Connection types, query text via DBM |
| **graphql** | `graphql-java-20/` (latest) | `BaseDecorator` (custom) | Field resolver instrumentation, query parsing, span per resolver, error tags from validation/execution |
| **grpc-client** | `grpc-1.5/` (`TracingClientInterceptor`) | `GrpcClientDecorator` | Use ClientInterceptor not method instrumentation, Metadata.Key for header propagation, streaming support (unary / server / client / bidi) |
| **grpc-server** | `grpc-1.5/` (`TracingServerInterceptor`) | `GrpcServerDecorator` | Use ServerInterceptor, MethodDescriptor for service/method name, header extraction via Metadata |
| **http-client** | `okhttp-3/`, `apache-httpclient-4/`, `apache-httpclient-5/` | `HttpClientDecorator` | Header injection via `AgentPropagation.Setter`, peer service from URL host, sync (`Call.execute`) + async (`Call.enqueue`) paths, response status tagging |
| **http-server** | `servlet/`, `netty-4.1/`, `jetty-9/` | `HttpServerDecorator` | Header extraction via `AgentPropagation.Getter`, route extraction (framework-specific), request/response tagging, async dispatch handling |
| **llm** | *(none yet in Java)* | — | No Java reference yet. See `dd-trace-py`'s `anthropic`, `openai` for the LLM integration shape. |
| **messaging** | `kafka-clients-0.11/`, `rabbitmq-amqp-2.7/`, `jms/` | `MessagingClientDecorator` | Producer (`Span.PRODUCER`) injects headers, Consumer (`Span.CONSUMER`) extracts, DSM checkpoints via `PathwayContext.setCheckpoint`, `messaging.destination` tag |
| **search** | `elasticsearch-rest-5/` (REST), `elasticsearch-transport-5/` (transport), `opensearch-rest-1/` | `ElasticsearchRestClientDecorator` (or `BaseDecorator`) | Action/operation extraction, index name in resource, host extraction for peer service, transport vs REST split |

## Picking The Right Reference Within a Category

When multiple references are listed, prefer the most recent major-version submodule — it reflects current patterns. Older submodules may use deprecated APIs (e.g., `okhttp-2/` predates `OkHttp3Decorator` patterns).

Common multi-version splits:
- **OkHttp**: `okhttp-2/` (v2.x), `okhttp-3/` (v3.x and v4.x compatible)
- **Apache HttpClient**: `apache-httpclient-4/` (v4.x), `apache-httpclient-5/` (v5.x — different package + builder API)
- **Jedis**: `jedis-1.4/`, `jedis-3.0/`, `jedis-4.0/`
- **Kafka clients**: `kafka-clients-0.11/`, `kafka-clients-2.0/`
- **AWS SDK**: `aws-java-sdk-1.11/` (v1, RequestHandler-based), `aws-java-sdk-2.2/` (v2, ExecutionInterceptor-based)
- **Elasticsearch**: `elasticsearch-rest-{5,6,7}`, `elasticsearch-transport-{5,6,7}`

## What to Read in the Reference

For any reference integration, in this order:

1. **`build.gradle`** — see the `muzzle { pass { ... } }` directives, `compileOnly` deps, `latestDepTestLibrary` pin
2. **`*Instrumentation.java` (InstrumenterModule)** — type matchers, method matchers, helper class names, context store declarations
3. **`*Decorator.java`** — span name / type / kind, tag conventions, error handling
4. **`*Advice.java`** — span lifecycle (start → activate → finish → close), `@Advice.Local`, `@Advice.OnMethodEnter` / `OnMethodExit` annotations
5. **`src/test/groovy/.../*Test.groovy`** — Spock spec patterns, `runUnderTrace` helper, `TEST_WRITER.waitForTraces` assertions

## Common Anti-Patterns

See [`anti-patterns.md`](anti-patterns.md) for what NOT to do — covers loggers in Advice classes, lambdas, missing helpers, wrong base decorator, etc.
