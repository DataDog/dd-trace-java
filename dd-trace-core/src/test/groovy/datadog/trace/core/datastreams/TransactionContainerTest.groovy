package datadog.trace.core.datastreams

import datadog.trace.api.datastreams.TransactionInfo
import datadog.trace.core.test.DDCoreSpecification

class TransactionContainerTest extends DDCoreSpecification {
  def "test with no resize"() {
    given:
    TransactionInfo.resetCache()
    def container = new TransactionContainer(1024)
    container.add(new TransactionInfo("1", 1, "1"))
    container.add(new TransactionInfo("2", 2, "2"))
    def data = container.getData()

    expect:
    data.size() == 22
    data == new byte[] {
      1, 0, 0, 0, 0, 0, 0, 0, 1, 1, 49, 2, 0, 0, 0, 0, 0, 0, 0, 2, 1, 50
    }
  }

  def "test with with resize"() {
    given:
    TransactionInfo.resetCache()
    def container = new TransactionContainer(10)
    container.add(new TransactionInfo("1", 1, "1"))
    container.add(new TransactionInfo("2", 2, "2"))
    def data = container.getData()

    expect:
    data.size() == 22
    data == new byte[] {
      1, 0, 0, 0, 0, 0, 0, 0, 1, 1, 49, 2, 0, 0, 0, 0, 0, 0, 0, 2, 1, 50
    }
  }

  def "test checkpoint map"() {
    given:
    TransactionInfo.resetCache()
    new TransactionInfo("1", 1, "1")
    new TransactionInfo("2", 2, "2")
    def data = TransactionInfo.getCheckpointIdCacheBytes()
    expect:
    data.size() == 6
    data == new byte[] {
      1, 1, 49, 2, 1, 50
    }
  }
}
