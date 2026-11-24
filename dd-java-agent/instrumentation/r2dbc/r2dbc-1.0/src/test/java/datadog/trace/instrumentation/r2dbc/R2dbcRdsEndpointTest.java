package datadog.trace.instrumentation.r2dbc;

import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TagsMatcher.defaultTags;
import static datadog.trace.agent.test.assertions.TagsMatcher.tag;
import static datadog.trace.agent.test.assertions.TraceMatcher.SORT_BY_START_TIME;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.r2dbc.R2dbcInstrumentationTest.H2_QUERY;
import static datadog.trace.instrumentation.r2dbc.R2dbcInstrumentationTest.eqs;
import static datadog.trace.test.junit.utils.assertions.Matchers.any;

import datadog.context.ContextScope;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.api.DDSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactoryOptions;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Verifies that R2DBC query spans carry the identity encoded in an Amazon RDS endpoint HOST. H2's
 * in-memory protocol ignores HOST, but the R2DBC SPI keeps it in the options the decorator reads.
 */
class R2dbcRdsEndpointTest extends AbstractInstrumentationTest {

  @Test
  void tagsRdsClusterReaderEndpoint() {
    runSelect("orders.cluster-ro-c9akciq32bzq.us-east-1.rds.amazonaws.com");

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span().root().operationName("parent"),
            span()
                .childOfPrevious()
                .operationName(H2_QUERY)
                .resourceName(eqs("SELECT ?"))
                .type(DDSpanTypes.SQL)
                .measured()
                .tags(
                    tag(Tags.COMPONENT, eqs("r2dbc")),
                    tag(Tags.SPAN_KIND, eqs(Tags.SPAN_KIND_CLIENT)),
                    tag(Tags.DB_TYPE, eqs("h2")),
                    tag(Tags.DB_OPERATION, eqs("SELECT")),
                    tag(Tags.DB_INSTANCE, eqs("rdsdb")),
                    tag(
                        Tags.PEER_HOSTNAME,
                        eqs("orders.cluster-ro-c9akciq32bzq.us-east-1.rds.amazonaws.com")),
                    tag("dbclusteridentifier", eqs("orders")),
                    tag("aws.rds.endpoint_type", eqs("cluster-ro")),
                    tag("aws.region", eqs("us-east-1")),
                    tag("_dd.svc_src", any()),
                    defaultTags())));
  }

  @Test
  void leavesOtherHostsUntagged() {
    runSelect("db.example.com");

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span().root().operationName("parent"),
            span()
                .childOfPrevious()
                .operationName(H2_QUERY)
                .resourceName(eqs("SELECT ?"))
                .type(DDSpanTypes.SQL)
                .measured()
                .tags(
                    tag(Tags.COMPONENT, eqs("r2dbc")),
                    tag(Tags.SPAN_KIND, eqs(Tags.SPAN_KIND_CLIENT)),
                    tag(Tags.DB_TYPE, eqs("h2")),
                    tag(Tags.DB_OPERATION, eqs("SELECT")),
                    tag(Tags.DB_INSTANCE, eqs("rdsdb")),
                    tag(Tags.PEER_HOSTNAME, eqs("db.example.com")),
                    tag("_dd.svc_src", any()),
                    defaultTags())));
  }

  private void runSelect(String host) {
    Connection connection =
        Mono.from(
                ConnectionFactories.get(
                        ConnectionFactoryOptions.builder()
                            .option(ConnectionFactoryOptions.DRIVER, "h2")
                            .option(ConnectionFactoryOptions.PROTOCOL, "mem")
                            .option(ConnectionFactoryOptions.HOST, host)
                            .option(ConnectionFactoryOptions.DATABASE, "rdsdb")
                            .build())
                    .create())
            .block();
    try {
      AgentSpan parent = startSpan("test", "parent");
      try (ContextScope scope = activateSpan(parent)) {
        Flux.from(connection.createStatement("SELECT 1").execute())
            .flatMap(result -> result.map((row, metadata) -> row.get(0)))
            .collectList()
            .block();
      } finally {
        parent.finish();
      }
    } finally {
      Mono.from(connection.close()).block();
    }
  }
}
