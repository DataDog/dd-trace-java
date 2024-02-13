package datadog.trace.api.civisibility.telemetry

import spock.lang.Specification

import java.util.stream.Collectors

class CiVisibilityCountMetricTest extends Specification {

  /**
   * This test enumerates every possible combination of tags for every metric (including omitting tags),
   * and for every combination it verifies the following:
   * <ul>
   *  <li>that it maps to a unique index (meaning no other combination maps to the same index)</li>
   *  <li>that tags that were used to compute the index can be derived from the index value</li>
   * </ul>
   */
  def "test metric indices"() {
    setup:
    Map<Integer, TagValue[]> indices = new HashMap<>()

    expect:
    for (CiVisibilityCountMetric metric : CiVisibilityCountMetric.values()) {
      def metricTags = metric.getTags()
      // iterate over all possible combinations of metric tags
      for (TagValue[] tags : cartesianProduct(metricTags)) {
        def index = metric.getIndex(tags)
        def existingTags = indices.put(index, tags)
        if (existingTags != null) {
          // verify that every metric+tags combination maps to a unique index
          throw new AssertionError("Index $index maps both to ${getTagsDescription(tags)} and to ${getTagsDescription(existingTags)}")
        }

        def tagValues = metric.getTagValues(index)
        if (tagValues != tags) {
          // verify that getting tag values by index returns the exact same value that were used for index calculation
          throw new AssertionError("Original tags ${getTagsDescription(tags)} are not equal to tags calculated from index $index: ${getTagsDescription(tagValues)}")
        }
      }
    }
  }

  private Collection<TagValue[]> cartesianProduct(Class<? extends TagValue>[] sets) {
    Collection<TagValue[]> tuples = new ArrayList<>()
    cartesianProductBacktrack(sets, tuples, new ArrayDeque<>(), 0)
    return tuples
  }

  private void cartesianProductBacktrack(Class<? extends TagValue>[] sets, Collection<TagValue[]> tuples, Deque<TagValue> currentTuple, int offset) {
    if (offset == sets.length) {
      int idx = 0
      TagValue[] tuple = new TagValue[currentTuple.size()]
      for (TagValue element : currentTuple) {
        tuple[tuple.length - ++idx] = element
      }
      tuples.add(tuple)
      return
    }

    // a branch where we omit current tag
    cartesianProductBacktrack(sets, tuples, currentTuple, offset + 1)

    for (TagValue element : sets[offset].getEnumConstants()) {
      currentTuple.push(element)
      cartesianProductBacktrack(sets, tuples, currentTuple, offset + 1)
      currentTuple.pop()
    }
  }

  private static String getTagsDescription(TagValue[] tags) {
    return "[" + Arrays.stream(tags).map(tag -> "${tag.getDeclaringClass().getSimpleName()}($tag)").collect(Collectors.joining(",")) + "]"
  }
}
