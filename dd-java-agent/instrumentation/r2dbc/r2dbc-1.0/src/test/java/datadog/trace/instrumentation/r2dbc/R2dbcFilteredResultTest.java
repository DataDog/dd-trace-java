package datadog.trace.instrumentation.r2dbc;

import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TraceMatcher.SORT_BY_START_TIME;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.r2dbc.R2dbcInstrumentationTest.H2_QUERY;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.context.ContextScope;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.api.DDSpanTypes;
import datadog.trace.core.DDSpan;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.Result;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class R2dbcFilteredResultTest extends AbstractInstrumentationTest {
  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  @Test
  void filteredResultConsumptionFinishesQuerySpan() {
    Connection connection =
        Mono.from(
                ConnectionFactories.get("r2dbc:h2:mem:///filtered_result_" + UUID.randomUUID())
                    .create())
            .block(TIMEOUT);
    DDSpan parent = (DDSpan) startSpan("test", "parent");
    try (ContextScope scope = activateSpan(parent)) {
      assertEquals(
          singletonList(42),
          Flux.from(connection.createStatement("SELECT 42").execute())
              .flatMap(
                  result ->
                      result
                          .filter(segment -> segment instanceof Result.RowSegment)
                          .flatMap(
                              segment ->
                                  Mono.just(
                                      ((Result.RowSegment) segment).row().get(0, Integer.class))))
              .collectList()
              .block(TIMEOUT));
    } finally {
      parent.finish();
      Mono.from(connection.close()).block(TIMEOUT);
    }

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span().root().operationName("parent"),
            span()
                .childOf(parent.getSpanId())
                .operationName(H2_QUERY)
                .type(DDSpanTypes.SQL)
                .measured()
                .error(false)));
  }
}
