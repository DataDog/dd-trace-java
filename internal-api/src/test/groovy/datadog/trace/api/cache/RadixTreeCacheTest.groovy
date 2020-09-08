package datadog.trace.api.cache

import datadog.trace.util.test.DDSpecification

class RadixTreeCacheTest extends DDSpecification {

  def "cached values are equal to autoboxed values" () {
    setup:
    RadixTreeCache<Integer> cache = new RadixTreeCache<>(4, 4, { it })
    when:
    Integer cached = cache.get(primitive)
    then:
    cached == Integer.valueOf(primitive)

    where:
    primitive << [-1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17]
  }

  def "cache ports" () {
    expect:
    Integer.valueOf(port) == RadixTreeCache.PORTS.get(port)
    where:
    port << [0, 80, 443, 4444, 8080, 65535]
  }

  def "cache HTTP statuses" () {
    expect:
    Integer.valueOf(status) == RadixTreeCache.HTTP_STATUSES.get(status)
    where:
    status << [0, 200, 201, 404, 329, 599, 700]
  }
}
