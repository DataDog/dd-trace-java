package datadog.trace.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.api.KnownTags;
import datadog.trace.common.writer.ListWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code DDSpan.setTag(long, ...)}: setting a known tag by id behaves exactly like setting it by
 * name, including interception.
 */
class DDSpanSetTagByIdTest extends DDCoreJavaSpecification {
  private CoreTracer tracer;
  private DDSpan span;

  @BeforeEach
  void setup() {
    tracer = tracerBuilder().writer(new ListWriter()).build();
    span = (DDSpan) tracer.buildSpan("datadog", "fakeOperation").start();
  }

  @AfterEach
  void tearDown() {
    span.finish();
    tracer.close();
  }

  @Test
  void storesTheTagUnderItsName() {
    span.setTag(KnownTags.HTTP_RESEND_COUNT, 2);
    span.setTag(KnownTags.PEER_HOSTNAME, "db.internal");
    span.setTag(KnownTags.DD_PROFILING_ENABLED, true);

    assertEquals(2, span.getTag(KnownTags.HTTP_RESEND_COUNT_NAME));
    assertEquals("db.internal", span.getTag(KnownTags.PEER_HOSTNAME_NAME));
    assertEquals(true, span.getTag(KnownTags.DD_PROFILING_ENABLED_NAME));
  }

  @Test
  void aNullOrEmptyValueRemovesTheTagById() {
    span.setTag(KnownTags.PEER_HOSTNAME, "db.internal");
    span.setTag(KnownTags.COMPONENT, "okhttp");

    span.setTag(KnownTags.PEER_HOSTNAME, (String) null);
    span.setTag(KnownTags.COMPONENT, "");

    assertNull(span.getTag(KnownTags.PEER_HOSTNAME_NAME));
    assertNull(span.getTag(KnownTags.COMPONENT_NAME));
  }

  @Test
  void clearingAnUnknownIdIsIgnored() {
    span.setTag(0L, (String) null);
    span.setTag(0L, "");
    span.setTag(0L, (Object) null);

    assertNull(span.getTag(KnownTags.PEER_HOSTNAME_NAME));
  }

  @Test
  void anIdReplacesAValueSetUnderTheName() {
    span.setTag("peer.port", 8080);

    span.setTag(KnownTags.PEER_SERVER_PORT, 443);

    assertEquals(443, span.getTag("peer.port"));

    span.setTag(KnownTags.PEER_SERVER_PORT, (Object) null);

    assertNull(span.getTag("peer.port"));
  }

  @Test
  void removingTheSpanKindByIdClearsTheKind() {
    span.setTag(KnownTags.SPAN_KIND, "client");
    assertEquals("client", span.getSpanKindString());

    span.setTag(KnownTags.SPAN_KIND, (String) null);

    assertNull(span.getSpanKindString());
    assertNull(span.getTag(KnownTags.SPAN_KIND_NAME));
  }

  @Test
  void interceptedTagsAreStillIntercepted() {
    span.setTag(KnownTags.DB_STATEMENT, "select 1");
    span.setTag(KnownTags.SERVICE, "orders-db");

    assertEquals("select 1", span.getResourceName().toString());
    assertNull(span.getTag(KnownTags.DB_STATEMENT_NAME));
    assertEquals("orders-db", span.getServiceName());
  }

  @Test
  void httpStatusCodeSetsTheStatusField() {
    span.setTag(KnownTags.HTTP_STATUS_CODE, 503);

    assertEquals(503, span.getHttpStatusCode());
  }

  @Test
  void anEmptyOrNullValueRemovesTheTag() {
    span.setTag(KnownTags.PEER_HOSTNAME, "db.internal");
    span.setTag(KnownTags.PEER_HOSTNAME, "");
    assertNull(span.getTag(KnownTags.PEER_HOSTNAME_NAME));

    span.setTag(KnownTags.PEER_HOSTNAME, (CharSequence) "db.internal");
    span.setTag(KnownTags.PEER_HOSTNAME, (CharSequence) null);
    assertNull(span.getTag(KnownTags.PEER_HOSTNAME_NAME));
  }
}
