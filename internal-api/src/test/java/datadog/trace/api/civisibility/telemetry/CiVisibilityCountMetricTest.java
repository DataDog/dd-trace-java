package datadog.trace.api.civisibility.telemetry;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class CiVisibilityCountMetricTest {

  /**
   * This test enumerates every possible combination of tags for every metric (including omitting
   * tags), and for every combination it verifies the following:
   *
   * <ul>
   *   <li>that it maps to a unique index (meaning no other combination maps to the same index)
   *   <li>that tags that were used to compute the index can be derived from the index value
   * </ul>
   */
  @Test
  void testMetricIndices() {
    Map<Integer, TagValue[]> indices = new HashMap<>();

    for (CiVisibilityCountMetric metric : CiVisibilityCountMetric.values()) {
      Class<? extends TagValue>[] metricTags = metric.getTags();
      // iterate over all possible combinations of metric tags
      for (TagValue[] tags : cartesianProduct(metricTags)) {
        int index = metric.getIndex(tags);
        TagValue[] existingTags = indices.put(index, tags);
        // verify that every metric+tags combination maps to a unique index
        assertNull(
            existingTags,
            () ->
                "Index "
                    + index
                    + " maps both to "
                    + getTagsDescription(tags)
                    + " and to "
                    + getTagsDescription(existingTags));

        TagValue[] tagValues = metric.getTagValues(index);
        // verify that getting tag values by index returns the exact same value that were used for
        // index calculation
        assertArrayEquals(
            tags,
            tagValues,
            () ->
                "Original tags "
                    + getTagsDescription(tags)
                    + " are not equal to tags calculated from index "
                    + index
                    + ": "
                    + getTagsDescription(tagValues));
      }
    }
  }

  private static Collection<TagValue[]> cartesianProduct(Class<? extends TagValue>[] sets) {
    Collection<TagValue[]> tuples = new ArrayList<>();
    cartesianProductBacktrack(sets, tuples, new ArrayDeque<>(), 0);
    return tuples;
  }

  private static void cartesianProductBacktrack(
      Class<? extends TagValue>[] sets,
      Collection<TagValue[]> tuples,
      Deque<TagValue> currentTuple,
      int offset) {
    if (offset == sets.length) {
      int idx = 0;
      TagValue[] tuple = new TagValue[currentTuple.size()];
      for (TagValue element : currentTuple) {
        tuple[tuple.length - ++idx] = element;
      }
      tuples.add(tuple);
      return;
    }

    // a branch where we omit current tag
    cartesianProductBacktrack(sets, tuples, currentTuple, offset + 1);

    for (TagValue element : sets[offset].getEnumConstants()) {
      currentTuple.push(element);
      cartesianProductBacktrack(sets, tuples, currentTuple, offset + 1);
      currentTuple.pop();
    }
  }

  private static String getTagsDescription(TagValue[] tags) {
    return "["
        + Arrays.stream(tags)
            .map(tag -> tag.getDeclaringClass().getSimpleName() + "(" + tag + ")")
            .collect(Collectors.joining(","))
        + "]";
  }
}
