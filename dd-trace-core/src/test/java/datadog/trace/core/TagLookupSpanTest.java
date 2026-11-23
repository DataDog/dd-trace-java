package datadog.trace.core;

import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
    String[] actual = context.unsafeGetTagStrings(LOOKUP);
    assertArrayEquals(expectedFound == 0 ? null : expected, actual);
    span.finish();
  }

  /**
   * Spans holding none of the lookup's tags read nothing and allocate nothing, even when they hold
   * custom tags (the Kafka consume shape) or known tags whose slots collide with the lookup's.
   */
  @TableTest({
    "scenario                | kind    ",
    "kafka consume, custom   | consumer",
    "http server, known tags | server  ",
    "no tags                 |         "
  })
  void aSpanWithNoneOfTheTagsAllocatesNothing(String scenario, String kind) {
    AgentSpan span =
        kind == null
            ? tracer.buildSpan("datadog", "op").start()
            : tracer.buildSpan("datadog", "op").withTag(SPAN_KIND, kind).start();
    if ("consumer".equals(kind)) {
      span.setTag("component", "java-kafka");
      span.setTag("kafka.partition", 0);
      span.setTag("kafka.offset", 123456L);
    } else if ("server".equals(kind)) {
      span.setTag("component", "tomcat-server");
      span.setTag("http.method", "GET");
      span.setTag("http.route", "/owners");
      span.setTag("http.status_code", 200);
      span.setTag("http.url", "http://localhost:8080/owners?lastName=");
    }
    assertNull(((DDSpanContext) span.spanContext()).unsafeGetTagStrings(LOOKUP));
    span.finish();
  }

  /**
   * Both spellings of one tag get its value whatever the span holds: with few tags on the span the
   * store scans its entries, and every name sharing the found id must be filled.
   */
  @Test
  void bothSpellingsOfOneTagGetItsValue() {
    // more known names than the span holds tags (about 9, mostly defaults), so the store scans
    TagLookup lookup =
        TagLookup.of(
            "http.method",
            "http.request.method",
            "peer.service",
            "db.instance",
            "db.system",
            "db.user",
            "http.url",
            "http.status_code",
            "http.route",
            "component",
            "peer.hostname",
            "rpc.service");
    AgentSpan span = tracer.buildSpan("datadog", "op").start();
    span.setTag("http.method", "GET");
    DDSpanContext context = (DDSpanContext) span.spanContext();

    String[] actual = context.unsafeGetTagStrings(lookup);
    assertEquals("GET", context.unsafeGetTag("http.request.method"));
    assertEquals("GET", actual[0]);
    assertEquals("GET", actual[1]);
    span.finish();
  }
}
