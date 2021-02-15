package datadog.trace.common.writer

import datadog.trace.core.DDSpan
import datadog.trace.test.util.DDSpecification

class MultiWriterTest extends DDSpecification {

  def "test that multi writer delegates to all"() {
    setup:
    def writers = new Writer[3]
    Writer mockW1 = Mock()
    Writer mockW2 = Mock()
    writers[0] = mockW1
    // null in position 1 to check that we skip that
    writers[2] = mockW2
    def writer = new MultiWriter(writers)
    List<DDSpan> trace = new LinkedList<>()

    when:
    writer.start()

    then:
    1 * mockW1.start()
    1 * mockW2.start()
    0 * _

    when:
    writer.write(trace)

    then:
    1 * mockW1.write({ it == trace })
    1 * mockW2.write({ it == trace })
    0 * _

    when:
    def flushed = writer.flush()

    then:
    1 * mockW1.flush() >> true
    1 * mockW2.flush() >> true
    0 * _
    flushed

    when:
    def notFlushed = writer.flush()

    then:
    1 * mockW1.flush() >> true
    1 * mockW2.flush() >> false
    0 * _
    !notFlushed

    when:
    writer.close()

    then:
    1 * mockW1.close()
    1 * mockW2.close()
    0 * _

    when:
    writer.incrementDropCounts(0)

    then:
    1 * mockW1.incrementDropCounts(0)
    1 * mockW2.incrementDropCounts(0)
    0 * _
  }
}
