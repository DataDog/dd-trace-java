package datadog.trace.civisibility.writer.ddintake

import com.fasterxml.jackson.databind.ObjectMapper
import datadog.communication.serialization.GrowableBuffer
import datadog.communication.serialization.msgpack.MsgPackWriter
import datadog.trace.api.civisibility.coverage.TestReport
import datadog.trace.api.civisibility.coverage.TestReportFileEntry
import datadog.trace.api.civisibility.coverage.TestReportHolder
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes
import datadog.trace.core.CoreSpan
import datadog.trace.core.propagation.PropagationTags
import datadog.trace.core.test.DDCoreSpecification
import org.msgpack.jackson.dataformat.MessagePackFactory
import spock.lang.Shared

import java.nio.ByteBuffer
import java.nio.channels.WritableByteChannel

class CiTestCovMapperV2Test extends DDCoreSpecification {

  @Shared
  ObjectMapper objectMapper = new ObjectMapper(new MessagePackFactory())

  def "test writes message"() {
    given:
    def trace = givenTrace(new TestReport(1, 2, 3, [new TestReportFileEntry("source", [new TestReportFileEntry.Segment(4, -1, 4, -1, 11)])]))

    when:
    def message = getMappedMessage(trace)

    then:
    message == [
      version  : 2,
      coverages: [
        [
          test_session_id: 1,
          test_suite_id  : 2,
          span_id        : 3,
          files          : [
            [
              filename: "source",
              segments: [[4, -1, 4, -1, 11]]
            ]
          ]
        ]
      ]
    ]
  }

  def "test writes message with multiple files and multiple lines"() {
    given:
    def trace = givenTrace(new TestReport(1, 2, 3, [
      new TestReportFileEntry("sourceA", [
        new TestReportFileEntry.Segment(4, -1, 4, -1, 1),
        new TestReportFileEntry.Segment(5, -1, 5, -1, 1)
      ]),
      new TestReportFileEntry("sourceB", [
        new TestReportFileEntry.Segment(20, -1, 20, -1, 1),
        new TestReportFileEntry.Segment(21, -1, 21, -1, 1)
      ])
    ]))

    when:
    def message = getMappedMessage(trace)

    then:
    message == [
      version  : 2,
      coverages: [
        [
          test_session_id: 1,
          test_suite_id  : 2,
          span_id        : 3,
          files          : [
            [
              filename: "sourceA",
              segments: [[4, -1, 4, -1, 1], [5, -1, 5, -1, 1]]
            ],
            [
              filename: "sourceB",
              segments: [[20, -1, 20, -1, 1], [21, -1, 21, -1, 1]]
            ]
          ]
        ]
      ]
    ]
  }

  def "test writes message with multiple reports"() {
    given:
    def trace = givenTrace(
      new TestReport(1, 2, 3, [
        new TestReportFileEntry("sourceA", [new TestReportFileEntry.Segment(14, -1, 14, -1, 1),])
      ]),
      new TestReport(1, 2, 4, [
        new TestReportFileEntry("sourceB", [new TestReportFileEntry.Segment(24, -1, 24, -1, 1),])
      ]),
      )

    when:
    def message = getMappedMessage(trace)

    then:
    message == [
      version  : 2,
      coverages: [
        [
          test_session_id: 1,
          test_suite_id  : 2,
          span_id        : 3,
          files          : [
            [
              filename: "sourceA",
              segments: [[14, -1, 14, -1, 1]]
            ]
          ]
        ],
        [
          test_session_id: 1,
          test_suite_id  : 2,
          span_id        : 4,
          files          : [
            [
              filename: "sourceB",
              segments: [[24, -1, 24, -1, 1]]
            ]
          ]
        ]
      ]
    ]
  }

  def "skips spans that have no reports"() {
    given:
    def trace = givenTrace(null, new TestReport(1, 2, 3, [new TestReportFileEntry("source", [new TestReportFileEntry.Segment(4, -1, 4, -1, 11)])]), null)

    when:
    def message = getMappedMessage(trace)

    then:
    message == [
      version  : 2,
      coverages: [
        [
          test_session_id: 1,
          test_suite_id  : 2,
          span_id        : 3,
          files          : [
            [
              filename: "source",
              segments: [[4, -1, 4, -1, 11]]
            ]
          ]
        ]
      ]
    ]
  }

  def "skips empty reports"() {
    given:
    def trace = givenTrace(
      new TestReport(1, 2, 3, [
        new TestReportFileEntry("source", [new TestReportFileEntry.Segment(4, -1, 4, -1, 11)])
      ]),
      new TestReport(1, 2, 4, [])
      )

    when:
    def message = getMappedMessage(trace)

    then:
    message == [
      version  : 2,
      coverages: [
        [
          test_session_id: 1,
          test_suite_id  : 2,
          span_id        : 3,
          files          : [
            [
              filename: "source",
              segments: [[4, -1, 4, -1, 11]]
            ]
          ]
        ]
      ]
    ]
  }

  def "skips duplicate reports"() {
    given:
    def trace = new ArrayList()

    def report = new TestReport(1, 2, 3, [new TestReportFileEntry("source", [new TestReportFileEntry.Segment(4, -1, 4, -1, 11)])])

    trace.add(buildSpan(0, InternalSpanTypes.TEST, PropagationTags.factory().empty(), [:], PrioritySampling.SAMPLER_KEEP, new DummyReportHolder(report)))
    trace.add(buildSpan(0, "testChild", PropagationTags.factory().empty(), [:], PrioritySampling.SAMPLER_KEEP, new DummyReportHolder(report)))

    when:
    def message = getMappedMessage(trace)

    then:
    message == [
      version  : 2,
      coverages: [
        [
          test_session_id: 1,
          test_suite_id  : 2,
          span_id        : 3,
          files          : [
            [
              filename: "source",
              segments: [[4, -1, 4, -1, 11]]
            ]
          ]
        ]
      ]
    ]
  }

  private List<? extends CoreSpan<?>> givenTrace(TestReport... testReports) {
    def trace = new ArrayList()
    for (TestReport testReport : testReports) {
      def testReportHolder = new DummyReportHolder(testReport)
      trace.add(buildSpan(0, InternalSpanTypes.TEST, PropagationTags.factory().empty(), [:], PrioritySampling.SAMPLER_KEEP, testReportHolder))
    }
    return trace
  }

  private Map getMappedMessage(List<? extends CoreSpan<?>> trace) {
    def buffer = new GrowableBuffer(1024)
    def mapper = new CiTestCovMapperV2(false)
    mapper.map(trace, new MsgPackWriter(buffer))

    WritableByteChannel channel = new ByteArrayWritableByteChannel()

    def slice = buffer.slice()
    def payload = mapper.newPayload().withBody(1, slice)
    payload.writeTo(channel)

    def writtenBytes = channel.toByteArray()
    return objectMapper.readValue(writtenBytes, Map)
  }

  private static final class DummyReportHolder implements TestReportHolder {
    private final testReport

    DummyReportHolder(testReport) {
      this.testReport = testReport
    }

    @Override
    TestReport getReport() {
      testReport
    }
  }

  private static final class ByteArrayWritableByteChannel implements WritableByteChannel {

    private final ByteArrayOutputStream outputStream = new ByteArrayOutputStream()

    @Override
    int write(ByteBuffer src) throws IOException {
      int remaining = src.remaining()
      byte[] buffer = new byte[remaining]
      src.get(buffer)
      outputStream.write(buffer)
      return remaining
    }

    @Override
    boolean isOpen() {
      return true
    }

    @Override
    void close() throws IOException {
      outputStream.close()
    }

    byte[] toByteArray() {
      return outputStream.toByteArray()
    }
  }
}
