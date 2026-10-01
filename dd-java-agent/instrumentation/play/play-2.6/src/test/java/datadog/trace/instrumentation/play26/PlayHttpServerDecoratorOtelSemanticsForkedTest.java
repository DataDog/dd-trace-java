package datadog.trace.instrumentation.play26;

import static datadog.trace.api.config.GeneralConfig.TRACE_OTEL_SEMANTICS_ENABLED;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpan;
import datadog.trace.test.junit.utils.config.WithConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.api.mvc.Result;
import play.api.mvc.Results$;

@WithConfig(key = TRACE_OTEL_SEMANTICS_ENABLED, value = "true")
class PlayHttpServerDecoratorOtelSemanticsForkedTest {
  private CoreTracer tracer;

  @BeforeEach
  void setUp() {
    tracer = CoreTracer.builder().writer(new ListWriter()).build();
  }

  @AfterEach
  void tearDown() {
    tracer.close();
  }

  @Test
  void doesNotReplaceMethodResourceNameForNotFoundResponse() {
    DDSpan span = (DDSpan) tracer.startSpan("test", "play.request");
    span.setResourceName("GET");
    Result notFound = Results$.MODULE$.NotFound();

    PlayHttpServerDecorator.DECORATE.updateOn404Only(span, notFound);

    assertEquals("GET", span.getResourceName().toString());
  }
}
