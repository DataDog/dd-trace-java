package datadog.trace.core;

import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import datadog.trace.api.TagLookup;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.common.writer.ListWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

/** The span's bulk tag read agrees with reading each name through unsafeGetTag. */
class TagLookupSpanTest extends DDCoreJavaSpecification {

  private static final TagLookup LOOKUP =
      TagLookup.of(
          "peer.service",
          "db.instance",
          "server.address", // direction-scoped: http.hostname inbound, peer.hostname outbound
          "my.custom.tag",
          "out.host",
          "messaging.destination.name",
          "absent.custom.tag");

  private CoreTracer tracer;

  @BeforeEach
  void setup() {
    tracer = tracerBuilder().writer(new ListWriter()).build();
  }

  @AfterEach
  void cleanup() {
    tracer.close();
  }

  @TableTest({
    "scenario      | kind     | hostTag      ",
    "server span   | server   | http.hostname",
    "client span   | client   | peer.hostname",
    "producer span | producer | peer.hostname",
    "consumer span | consumer | http.hostname",
    "internal span | internal | peer.hostname",
    "no kind       |          | peer.hostname"
  })
  void bulkReadMatchesUnsafeGetTag(String kind, String hostTag) {
    AgentSpan span =
        kind == null
            ? tracer.buildSpan("datadog", "op").start()
            : tracer.buildSpan("datadog", "op").withTag(SPAN_KIND, kind).start();
    span.setTag("peer.service", "billing");
    span.setTag("db.instance", 7);
    span.setTag(hostTag, "example.com");
    span.setTag("my.custom.tag", "custom-value");
    DDSpanContext context = (DDSpanContext) span.spanContext();

    String[] expected = new String[LOOKUP.size()];
    int expectedFound = 0;
    for (int i = 0; i < LOOKUP.size(); i++) {
      Object value = context.unsafeGetTag(LOOKUP.name(i));
      if (value != null) {
        expected[i] = value.toString();
        expectedFound++;
      }
    }
    String[] actual = new String[LOOKUP.size()];
    assertEquals(expectedFound, context.unsafeGetTagStrings(LOOKUP, actual));
    assertArrayEquals(expected, actual);
    span.finish();
  }

  @Test
  void aSpanWithNoneOfTheTagsHasNothingToRead() {
    AgentSpan span = tracer.buildSpan("datadog", "op").start();
    DDSpanContext context = (DDSpanContext) span.spanContext();
    String[] out = new String[LOOKUP.size()];
    assertEquals(0, context.unsafeGetTagStrings(LOOKUP, out));
    if (!context.mayHaveAnyTag(TagLookup.of("peer.service", "db.instance"))) {
      // a false is only possible when the span really holds none of them
      assertFalse(context.unsafeGetTag("peer.service") != null);
    }
    span.finish();
  }
}
