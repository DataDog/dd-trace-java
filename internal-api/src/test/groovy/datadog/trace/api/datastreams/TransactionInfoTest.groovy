package datadog.trace.api.datastreams

import datadog.trace.api.Pair
import spock.lang.Specification

class TransactionInfoTest extends Specification {

  def "test checkpoint id cache serialization multiple"() {
    given:
    TransactionInfo.resetCache()
    def controlSize = 10
    // generate multiple transaction ids to trigger cache updates
    for (int i = 0; i < controlSize; i++) {
      new TransactionInfo("id " + i, i, "checkpoint " + i)
    }

    def items = new LinkedList<Pair<String, Integer>>()
    // get cache data
    def data = TransactionInfo.getCheckpointIdCacheBytes()
    def i = 0
    while (i < data.size()) {
      def id = data[i]
      i++

      def size = data[i]
      i++

      def str = new String(data, i, size)
      i += size

      items.add(Pair.of(str, id) as Pair<String, Integer>)
    }

    expect:
    items.size() == controlSize

    for (def item in items) {
      item.left == "Checkpoint " + item.right
    }
  }

  def "test checkpoint id cache serialization"() {
    given:
    TransactionInfo.resetCache()
    new TransactionInfo("id", 1, "checkpoint")
    def bytes = TransactionInfo.getCheckpointIdCacheBytes()
    expect:
    bytes.size() == 12
    bytes == new byte[] {1, 10, 99, 104, 101, 99, 107, 112, 111, 105, 110, 116}
  }

  def "test transaction id serialization"() {
    given:
    TransactionInfo.resetCache()
    def test = new TransactionInfo("id", 1, "checkpoint")
    def bytes = test.getBytes()
    expect:
    bytes.size() == 12
    bytes == new byte[] {1, 0, 0, 0, 0, 0, 0, 0, 1, 2, 105, 100}
  }
}
