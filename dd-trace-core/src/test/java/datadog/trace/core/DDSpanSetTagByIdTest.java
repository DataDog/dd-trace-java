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
    span.setTag(KnownTags.PEER_PORT_ID, 5432);
    span.setTag(KnownTags.PEER_HOSTNAME_ID, "db.internal");
    span.setTag(KnownTags.DD_PROFILING_ENABLED_ID, true);

    assertEquals(5432, span.getTag(KnownTags.PEER_PORT_NAME));
    assertEquals("db.internal", span.getTag(KnownTags.PEER_HOSTNAME_NAME));
    assertEquals(true, span.getTag(KnownTags.DD_PROFILING_ENABLED_NAME));
  }

  @Test
  void interceptedTagsAreStillIntercepted() {
    span.setTag(KnownTags.DB_STATEMENT_ID, "select 1");
    span.setTag(KnownTags.SERVICE_ID, "orders-db");

    assertEquals("select 1", span.getResourceName().toString());
    assertNull(span.getTag(KnownTags.DB_STATEMENT_NAME));
    assertEquals("orders-db", span.getServiceName());
  }

  @Test
  void httpStatusCodeSetsTheStatusField() {
    span.setTag(KnownTags.HTTP_STATUS_CODE_ID, 503);

    assertEquals(503, span.getHttpStatusCode());
  }

  @Test
  void anEmptyOrNullValueRemovesTheTag() {
    span.setTag(KnownTags.PEER_HOSTNAME_ID, "db.internal");
    span.setTag(KnownTags.PEER_HOSTNAME_ID, "");
    assertNull(span.getTag(KnownTags.PEER_HOSTNAME_NAME));

    span.setTag(KnownTags.PEER_HOSTNAME_ID, (CharSequence) "db.internal");
    span.setTag(KnownTags.PEER_HOSTNAME_ID, (CharSequence) null);
    assertNull(span.getTag(KnownTags.PEER_HOSTNAME_NAME));
  }
}
