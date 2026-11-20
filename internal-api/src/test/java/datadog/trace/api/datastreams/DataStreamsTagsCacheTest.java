package datadog.trace.api.datastreams;

import static datadog.trace.api.datastreams.DataStreamsTags.Direction.INBOUND;
import static datadog.trace.api.datastreams.DataStreamsTags.Direction.OUTBOUND;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import datadog.trace.api.BaseHash;
import datadog.trace.api.datastreams.DataStreamsTags.Direction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DataStreamsTagsCacheTest {
  private long savedBaseHash;

  @BeforeEach
  void setUp() {
    savedBaseHash = BaseHash.getBaseHash();
    DataStreamsTags.setServiceNameOverride(null);
    DataStreamsTagsCache.clear();
  }

  @AfterEach
  void tearDown() {
    BaseHash.updateBaseHash(savedBaseHash);
    DataStreamsTags.setServiceNameOverride(null);
    DataStreamsTagsCache.clear();
  }

  private static void assertSameTags(DataStreamsTags expected, DataStreamsTags actual) {
    assertEquals(expected.getHash(), actual.getHash());
    assertEquals(expected.getAggregationHash(), actual.getAggregationHash());
    assertEquals(expected, actual);
    assertEquals(expected.nonNullSize(), actual.nonNullSize());
    for (int i = 0; i < expected.size(); i++) {
      assertEquals(expected.tagByIndex(i), actual.tagByIndex(i));
    }
    assertEquals(expected.getDirectionValue(), actual.getDirectionValue());
  }

  @Test
  void cachedTagsEqualTagsBuiltFromTheSameArguments() {
    String[] topics = {"orders", "payments", null};
    String[] groups = {"billing", null};
    String[] clusters = {"cluster-1", null};
    Direction[] directions = {INBOUND, OUTBOUND, null};
    for (int pass = 0; pass < 2; pass++) {
      for (String topic : topics) {
        for (String group : groups) {
          for (String cluster : clusters) {
            for (Direction direction : directions) {
              assertSameTags(
                  DataStreamsTags.create("kafka", direction, topic, group, cluster),
                  DataStreamsTagsCache.create("kafka", direction, topic, group, cluster));
            }
          }
        }
      }
    }
  }

  @Test
  void repeatedArgumentsReuseOneInstance() {
    DataStreamsTags first = DataStreamsTagsCache.create("kafka", INBOUND, "orders", "g", "c");
    DataStreamsTags second =
        DataStreamsTagsCache.create("kafka", INBOUND, new String("orders"), "g", "c");

    assertSame(first, second);
  }

  @Test
  void aBaseHashChangeIsPickedUp() {
    DataStreamsTags before = DataStreamsTagsCache.create("kafka", OUTBOUND, "orders", null, "c");

    BaseHash.updateBaseHash(savedBaseHash + 12);
    DataStreamsTags after = DataStreamsTagsCache.create("kafka", OUTBOUND, "orders", null, "c");

    assertNotSame(before, after);
    assertSameTags(DataStreamsTags.create("kafka", OUTBOUND, "orders", null, "c"), after);
  }

  @Test
  void theServiceNameOverrideIsPartOfTheKey() {
    ThreadLocal<String> override = new ThreadLocal<>();
    DataStreamsTags.setServiceNameOverride(override);
    DataStreamsTags unset = DataStreamsTagsCache.create("kafka", INBOUND, "orders", "g", "c");

    override.set("other-service");
    DataStreamsTags set = DataStreamsTagsCache.create("kafka", INBOUND, "orders", "g", "c");
    assertSameTags(DataStreamsTags.create("kafka", INBOUND, "orders", "g", "c"), set);

    override.remove();
    assertSameTags(unset, DataStreamsTagsCache.create("kafka", INBOUND, "orders", "g", "c"));
    assertEquals(
        DataStreamsTags.create("kafka", INBOUND, "orders", "g", "c").getHash(), unset.getHash());
  }

  @Test
  void collidingKeysStillReturnTheirOwnTags() {
    for (int i = 0; i < 5000; i++) {
      String topic = "topic-" + i;
      assertSameTags(
          DataStreamsTags.create("kafka", INBOUND, topic, "g", null),
          DataStreamsTagsCache.create("kafka", INBOUND, topic, "g", null));
    }
  }
}
