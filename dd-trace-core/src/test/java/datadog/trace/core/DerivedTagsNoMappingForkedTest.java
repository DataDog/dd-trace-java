package datadog.trace.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.KnownTags;
import datadog.trace.api.TagMap;
import datadog.trace.api.config.GeneralConfig;
import datadog.trace.api.config.TraceInstrumentationConfig;
import datadog.trace.api.config.TracerConfig;
import datadog.trace.bootstrap.instrumentation.api.SpanPrototype;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.tagprocessor.TagsPostProcessorFactory;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * With no peer-service mapping, formulas cover every eager post-processor, so a span with derived
 * tags skips the eager chain entirely. Checks that the spans still serialize exactly the same tags
 * as with the post-processors, comparing whole tag maps.
 */
@WithConfig(key = TracerConfig.TRACE_DENSE_TAGS_ENABLED, value = "true")
@WithConfig(key = TracerConfig.TRACE_PEER_SERVICE_DEFAULTS_ENABLED, value = "true")
@WithConfig(key = TraceInstrumentationConfig.TRACE_RESOURCE_RENAMING_ENABLED, value = "true")
@WithConfig(key = GeneralConfig.SERVICE_NAME, value = "checkout")
@WithConfig(key = GeneralConfig.VERSION, value = "1.2.3")
class DerivedTagsNoMappingForkedTest extends DDCoreJavaSpecification {

  @BeforeEach
  void addInternalTags() {
    TagsPostProcessorFactory.withAddInternalTags(true);
  }

  @AfterEach
  void restoreInternalTags() {
    TagsPostProcessorFactory.withAddInternalTags(false);
  }

  @Test
  void theEagerChainIsSkipped() {
    assertTrue(tracer(true).skipEagerTagProcessing());
  }

  @Test
  void wholeTagMapsMatchThePostProcessors() {
    CoreTracer legacy = tracer(false);
    CoreTracer derived = tracer(true);
    for (int shape = 0; shape < 4; ++shape) {
      assertEquals(tagsOf(legacy, shape), tagsOf(derived, shape), "shape " + shape);
    }
  }

  /** The serialized tags of one span shape, as a sorted name-to-string map. */
  private Map<String, String> tagsOf(CoreTracer tracer, int shape) {
    DDSpan span;
    switch (shape) {
      case 0: // a jdbc client span from a decorator-style prototype
        span =
            (DDSpan)
                tracer
                    .buildSpan(
                        SpanPrototype.builder()
                            .initInstrumentationName("jdbc")
                            .initComponentAndIntegration("java-jdbc")
                            .initKind(Tags.SPAN_KIND_CLIENT)
                            .build(),
                        "database.query")
                    .start();
        span.setServiceName("postgresql", "java-jdbc");
        span.setTag(KnownTags.DB_INSTANCE, "petclinic");
        span.setTag(KnownTags.PEER_HOSTNAME, "db.internal");
        break;
      case 1: // a server span with a route and a URL
        span = (DDSpan) tracer.buildSpan("tomcat", "servlet.request").start();
        span.setTag(KnownTags.SPAN_KIND, Tags.SPAN_KIND_SERVER);
        span.setTag(KnownTags.COMPONENT, "tomcat-server");
        span.setTag(KnownTags.HTTP_METHOD, "GET");
        span.setTag(KnownTags.HTTP_ROUTE, "*");
        span.setTag(KnownTags.HTTP_URL, "http://localhost:8080/owners/42");
        break;
      case 2: // a manual span with an explicit peer.service
        span = (DDSpan) tracer.buildSpan("manual", "call").start();
        span.setTag(KnownTags.SPAN_KIND, Tags.SPAN_KIND_CLIENT);
        span.setTag(KnownTags.PEER_SERVICE, "billing");
        break;
      default: // an internal span with an error
        span = (DDSpan) tracer.buildSpan("trace", "trace.annotation").start();
        span.addThrowable(new IllegalStateException("boom"));
        break;
    }
    span.finish();
    TagMap[] captured = {null};
    span.processTagsAndBaggage(metadata -> captured[0] = metadata.getTags().copy(), 0);
    Map<String, String> tags = new TreeMap<>();
    captured[0].forEach(tags, (m, entry) -> m.put(entry.tag(), entry.stringValue()));
    return tags;
  }

  private CoreTracer tracer(boolean derivedTags) {
    return tracerBuilder().writer(new ListWriter()).derivedTags(derivedTags).build();
  }
}
