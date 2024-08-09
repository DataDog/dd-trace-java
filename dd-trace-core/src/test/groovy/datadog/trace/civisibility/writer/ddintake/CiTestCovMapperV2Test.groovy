package datadog.trace.civisibility.writer.ddintake

import com.fasterxml.jackson.databind.ObjectMapper
import datadog.communication.serialization.GrowableBuffer
import datadog.communication.serialization.msgpack.MsgPackWriter
import datadog.trace.api.civisibility.coverage.CoverageProbes
import datadog.trace.api.civisibility.coverage.CoverageStore
import datadog.trace.api.civisibility.coverage.NoOpProbes
import datadog.trace.api.civisibility.coverage.TestReport
import datadog.trace.api.civisibility.coverage.TestReportFileEntry
import datadog.trace.api.civisibility.domain.TestContext
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
    def trace = givenTrace(new TestReport(1, 2, 3, [new TestReportFileEntry("source", BitSet.valueOf(new long[] {
        3, 5, 8
      }))]))

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
              bitmap: [3, 0, 0, 0, 0, 0, 0, 0, 5, 0, 0, 0, 0, 0, 0, 0, 8]
            ]
          ]
        ]
      ]
    ]
  }

  def "test writes message with multiple files and multiple lines"() {
    given:
    def trace = givenTrace(new TestReport(1, 2, 3, [
      new TestReportFileEntry("sourceA", BitSet.valueOf(new long[] {
        3, 5, 8
      })),
      new TestReportFileEntry("sourceB", BitSet.valueOf(new long[] {
        1, 255, 7
      }))
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
              bitmap:[3, 0, 0, 0, 0, 0, 0, 0, 5, 0, 0, 0, 0, 0, 0, 0, 8]
            ],
            [
              filename: "sourceB",
              bitmap:[1, 0, 0, 0, 0, 0, 0, 0, -1, 0, 0, 0, 0, 0, 0, 0, 7]
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
      new TestReportFileEntry("sourceA", BitSet.valueOf(new long[] {
        2, 17, 41
      }))
    ]),
    new TestReport(1, 2, 4, [
      new TestReportFileEntry("sourceB", BitSet.valueOf(new long[] {
        11, 13, 55
      }))
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
              bitmap:[2, 0, 0, 0, 0, 0, 0, 0, 17, 0, 0, 0, 0, 0, 0, 0, 41]
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
              bitmap:[11, 0, 0, 0, 0, 0, 0, 0, 13, 0, 0, 0, 0, 0, 0, 0, 55]
            ]
          ]
        ]
      ]
    ]
  }

  def "skips spans that have no reports"() {
    given:
    def trace = givenTrace(null, new TestReport(1, 2, 3, [new TestReportFileEntry("source", BitSet.valueOf(new long[] {
        83, 25, 48
      }))]), null)

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
              bitmap:[83, 0, 0, 0, 0, 0, 0, 0, 25, 0, 0, 0, 0, 0, 0, 0, 48]
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
      new TestReportFileEntry("source", BitSet.valueOf(new long[] {
        33, 53, 87
      }))
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
              bitmap:[33, 0, 0, 0, 0, 0, 0, 0, 53, 0, 0, 0, 0, 0, 0, 0, 87]
            ]
          ]
        ]
      ]
    ]
  }

  def "skips duplicate reports"() {
    given:
    def trace = new ArrayList()

    def report = new TestReport(1, 2, 3, [new TestReportFileEntry("source", BitSet.valueOf(new long[] {
        3, 5, 8
      }))])

    trace.add(buildSpan(0, InternalSpanTypes.TEST, PropagationTags.factory().empty(), [:], PrioritySampling.SAMPLER_KEEP, new DummyTestContext(new DummyReportHolder(report))))
    trace.add(buildSpan(0, "testChild", PropagationTags.factory().empty(), [:], PrioritySampling.SAMPLER_KEEP, new DummyTestContext(new DummyReportHolder(report))))

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
              bitmap:[3, 0, 0, 0, 0, 0, 0, 0, 5, 0, 0, 0, 0, 0, 0, 0, 8]
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
      trace.add(buildSpan(0, InternalSpanTypes.TEST, PropagationTags.factory().empty(), [:], PrioritySampling.SAMPLER_KEEP, new DummyTestContext(testReportHolder)))
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

  private static final class DummyReportHolder implements CoverageStore {
    private final testReport

    DummyReportHolder(testReport) {
      this.testReport = testReport
    }

    @Override
    TestReport getReport() {
      testReport
    }

    @Override
    boolean report(Long testSessionId, Long testSuiteId, long spanId) {
      return false
    }

    @Override
    CoverageProbes getProbes() {
      return NoOpProbes.INSTANCE
    }
  }

  private static final class DummyTestContext implements TestContext {
    private final CoverageStore coverageStore

    DummyTestContext(CoverageStore coverageStore) {
      this.coverageStore = coverageStore
    }

    @Override
    CoverageStore getCoverageStore() {
      return coverageStore
    }

    @Override
    def <T> void set(Class<T> key, T value) {
    }

    @Override
    def <T> T get(Class<T> key) {
      return null
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
