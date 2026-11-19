package datadog.trace.api.datastreams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.context.Context;
import org.junit.jupiter.api.Test;

class DataStreamsContextTest {

  @Test
  void testConstructor() {
    DataStreamsTags tags = DataStreamsTags.EMPTY;

    DataStreamsContext dsmContext = DataStreamsContext.fromTags(tags);

    assertEquals(tags, dsmContext.tags());
    assertEquals(0, dsmContext.defaultTimestamp());
    assertEquals(0, dsmContext.payloadSizeBytes());
    assertTrue(dsmContext.sendCheckpoint());

    dsmContext = DataStreamsContext.fromTagsWithoutCheckpoint(tags);

    assertEquals(tags, dsmContext.tags());
    assertEquals(0, dsmContext.defaultTimestamp());
    assertEquals(0, dsmContext.payloadSizeBytes());
    assertFalse(dsmContext.sendCheckpoint());

    long timestamp = 123L;
    long payloadSize = 456L;
    dsmContext = DataStreamsContext.create(tags, timestamp, payloadSize);

    assertEquals(tags, dsmContext.tags());
    assertEquals(timestamp, dsmContext.defaultTimestamp());
    assertEquals(payloadSize, dsmContext.payloadSizeBytes());
    assertTrue(dsmContext.sendCheckpoint());
  }

  @Test
  void testContextStore() {
    DataStreamsTags tags = DataStreamsTags.EMPTY;

    DataStreamsContext dsmContext = DataStreamsContext.fromTags(tags);
    Context context = dsmContext.storeInto(Context.root());

    assertEquals(dsmContext, DataStreamsContext.fromContext(context));
  }
}
