package datadog.trace.api.cache

import datadog.trace.test.util.DDSpecification

import java.util.concurrent.ThreadLocalRandom

import static datadog.trace.test.util.ThreadUtils.runConcurrently

class RadixTreeCacheTest extends DDSpecification {

  def "cached values are equal to autoboxed values"() {
    setup:
    RadixTreeCache<Integer> cache = new RadixTreeCache<>(4, 4, { it })
    when:
    Integer cached = cache.get(primitive)
    then:
    cached == Integer.valueOf(primitive)

    where:
    primitive << [
      -1,
      0,
      1,
      2,
      3,
      4,
      5,
      6,
      7,
      8,
      9,
      10,
      11,
      12,
      13,
      14,
      15,
      16,
      17
    ]
  }


  def "cached values are equal to computed values"() {
    setup:
    RadixTreeCache<String> cache = new RadixTreeCache<>(4, 4, { it.toString() })
    when:
    String cached = cache.get(primitive)
    then:
    cached == String.valueOf(primitive)

    where:
    primitive << [
      -1,
      0,
      1,
      2,
      3,
      4,
      5,
      6,
      7,
      8,
      9,
      10,
      11,
      12,
      13,
      14,
      15,
      16,
      17
    ]
  }

  def "cached values are equal to computed values with precomputing"() {
    setup:
    RadixTreeCache<String> cache = new RadixTreeCache<>(4, 4,
      { it.toString() }, -1, 9)
    when:
    String cached = cache.get(primitive)
    then:
    cached == String.valueOf(primitive)

    where:
    primitive << [
      -1,
      0,
      1,
      2,
      3,
      4,
      5,
      6,
      7,
      8,
      9,
      10,
      11,
      12,
      13,
      14,
      15,
      16,
      17
    ]
  }


  def "concurrency test"() {
    setup:
    RadixTreeCache<String> cache = new RadixTreeCache<>(256, 256,
      { it.toString() }, -1, 9)
    expect:
    runConcurrently(8, 10_000, {
      int value = ThreadLocalRandom.current().nextInt(70_000)
      assert cache.get(value) == String.valueOf(value)
    })
  }

  def "cache ports"() {
    expect:
    Integer.valueOf(port) == RadixTreeCache.PORTS.get(port)
    where:
    port << [0, 80, 443, 4444, 8080, 65535]
  }

  def "cache HTTP statuses"() {
    expect:
    Integer.toString(status) == RadixTreeCache.HTTP_STATUSES.get(status) as String
    where:
    status << [0, 200, 201, 404, 329, 599, 700]
  }
}
