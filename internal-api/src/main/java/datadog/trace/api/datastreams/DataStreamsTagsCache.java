package datadog.trace.api.datastreams;

import datadog.trace.api.BaseHash;
import java.util.Objects;

/**
 * Reuses {@link DataStreamsTags} built by {@link DataStreamsTags#create(String,
 * DataStreamsTags.Direction, String, String, String)} for repeated arguments.
 *
 * <p>Tags are immutable and their hashes depend only on the arguments, the base hash and the
 * current thread's service-name override, so an entry is reused only when all of those match. The
 * table is direct-mapped and lock-free: a collision overwrites the slot, and a racing reader either
 * sees a complete entry (all fields are final) or builds the tags itself.
 */
public final class DataStreamsTagsCache {
  private static final int SIZE = 1024; // power of two
  private static final Entry[] TABLE = new Entry[SIZE];

  private DataStreamsTagsCache() {}

  public static DataStreamsTags create(
      String type,
      DataStreamsTags.Direction direction,
      String topic,
      String group,
      String kafkaClusterId) {
    long baseHash = BaseHash.getBaseHash();
    String serviceOverride = DataStreamsTags.currentServiceNameOverride();
    int index = index(type, direction, topic, group, kafkaClusterId);
    Entry entry = TABLE[index];
    if (entry != null
        && entry.baseHash == baseHash
        && entry.direction == direction
        && Objects.equals(entry.topic, topic)
        && Objects.equals(entry.group, group)
        && Objects.equals(entry.kafkaClusterId, kafkaClusterId)
        && Objects.equals(entry.type, type)
        && Objects.equals(entry.serviceOverride, serviceOverride)) {
      return entry.tags;
    }
    DataStreamsTags tags = DataStreamsTags.create(type, direction, topic, group, kafkaClusterId);
    // The constructor reads the base hash again; don't cache tags built across a change of it
    if (BaseHash.getBaseHash() == baseHash) {
      TABLE[index] =
          new Entry(type, direction, topic, group, kafkaClusterId, baseHash, serviceOverride, tags);
    }
    return tags;
  }

  /** Empties the table; for tests. */
  static void clear() {
    for (int i = 0; i < SIZE; i++) {
      TABLE[i] = null;
    }
  }

  private static int index(
      String type,
      DataStreamsTags.Direction direction,
      String topic,
      String group,
      String kafkaClusterId) {
    int h = Objects.hashCode(topic);
    h = 31 * h + Objects.hashCode(group);
    h = 31 * h + Objects.hashCode(kafkaClusterId);
    h = 31 * h + Objects.hashCode(type);
    h = 31 * h + (direction == null ? 0 : direction.ordinal() + 1);
    h ^= h >>> 16;
    return h & (SIZE - 1);
  }

  private static final class Entry {
    final String type;
    final DataStreamsTags.Direction direction;
    final String topic;
    final String group;
    final String kafkaClusterId;
    final long baseHash;
    final String serviceOverride;
    final DataStreamsTags tags;

    Entry(
        String type,
        DataStreamsTags.Direction direction,
        String topic,
        String group,
        String kafkaClusterId,
        long baseHash,
        String serviceOverride,
        DataStreamsTags tags) {
      this.type = type;
      this.direction = direction;
      this.topic = topic;
      this.group = group;
      this.kafkaClusterId = kafkaClusterId;
      this.baseHash = baseHash;
      this.serviceOverride = serviceOverride;
      this.tags = tags;
    }
  }
}
