package datadog.trace.civisibility.writer.ddintake

import com.fasterxml.jackson.databind.ObjectMapper
import datadog.communication.serialization.GrowableBuffer
import datadog.communication.serialization.msgpack.MsgPackWriter
import datadog.trace.api.civisibility.coverage.TestReport
import datadog.trace.api.civisibility.coverage.TestReportFileEntry
import datadog.trace.api.civisibility.coverage.TestReportHolder
import datadog.trace.api.sampling.PrioritySampling
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
    def trace = givenTrace(new TestReport(1, 2, 3, [
      "source": new TestReportFileEntry("source").incrementLine(4, 5, 6)
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
      "sourceA": new TestReportFileEntry("sourceA")
      .incrementLine(4, 1, 0)
      .incrementLine(5, 1, 0),
      "sourceB": new TestReportFileEntry("sourceB")
      .incrementLine(20, 1, 0)
      .incrementLine(21, 1, 0)
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
        "sourceA": new TestReportFileEntry("sourceA").incrementLine(14, 1, 0)]),
      new TestReport(1, 2, 4, [
        "sourceB": new TestReportFileEntry("sourceB").incrementLine(24, 1, 0)])
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
    def trace = givenTrace(null,
      new TestReport(1, 2, 3, [
        "source": new TestReportFileEntry("source").incrementLine(4, 5, 6)
      ]),
      null
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

  def "skips empty reports"() {
    given:
    def trace = givenTrace(
      new TestReport(1, 2, 3, [
        "source": new TestReportFileEntry("source").incrementLine(4, 5, 6)
      ]),
      new TestReport(1, 2, 4, [:])
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

  private List<? extends CoreSpan<?>> givenTrace(TestReport... testReports) {
    def trace = new ArrayList()
    for (TestReport testReport : testReports) {
      def testReportHolder = new DummyReportHolder(testReport)
      trace.add(buildSpan(0, "spanType", PropagationTags.factory().empty(), [:], PrioritySampling.SAMPLER_KEEP, testReportHolder))
    }
    return trace
  }

  private Map getMappedMessage(List<? extends CoreSpan<?>> trace) {
    def buffer = new GrowableBuffer(1024)
    def mapper = new CiTestCovMapperV2()
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
