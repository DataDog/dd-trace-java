package datadog.trace.common.writer

import datadog.trace.common.writer.ddagent.DDAgentApi
import datadog.trace.common.writer.ddagent.Payload
import datadog.trace.common.writer.ddagent.TraceMapper
import datadog.trace.common.writer.ddagent.TraceMapperV0_4
import datadog.trace.common.writer.ddagent.TraceProcessingDisruptor
import datadog.trace.core.DDSpan
import datadog.trace.core.monitor.Monitor
import datadog.trace.core.serialization.msgpack.ByteBufferConsumer
import datadog.trace.core.serialization.msgpack.Packer
import datadog.trace.util.test.DDSpecification
import spock.lang.Ignore
import spock.lang.Retry

import java.nio.ByteBuffer
import java.util.concurrent.Phaser
import java.util.concurrent.atomic.AtomicInteger

import static datadog.trace.common.writer.DDAgentWriter.DISRUPTOR_BUFFER_SIZE
import static datadog.trace.core.SpanFactory.newSpanOf
import static java.util.concurrent.TimeUnit.SECONDS

@Retry
// TODO: these tests should be reworked with the latest "Payload" changes.
class TraceProcessingDisruptorTest extends DDSpecification {

  def phaser = new Phaser()
  def api = Mock(DDAgentApi) {
    sendSerializedTraces(_, _, _) >> DDAgentApi.Response.success(200)
  }
  def traceMapper = Mock(TraceMapper)
  def payload = Mock(Payload)
  def monitor = Mock(Monitor)

  def setup() {
    // Register for two threads.
    phaser.register()
    phaser.register()
  }

  def "test happy path"() {
    setup:
    def disruptor = new TraceProcessingDisruptor(
      2,
      monitor,
      api,
      -1,
      SECONDS,
      false)
    disruptor.start()

    when:
    disruptor.flush(1, SECONDS)

    then:
    0 * _

    when:
    assert disruptor.publish(trace, 1)
    assert disruptor.publish(trace, 1)
    assert disruptor.flush(1, SECONDS)

    then:
    1 * api.selectTraceMapper() >> traceMapper
    1 * traceMapper.messageBufferSize() >> (5 << 20)
    2 * traceMapper.map(trace, _)
    1 * traceMapper.newPayload() >> payload
    1 * payload.withRepresentativeCount(2) >> payload
    1 * payload.withBody(2, _) >> payload
    1 * payload.sizeInBytes() >> payloadSize
    1 * api.sendSerializedTraces(payload) >> response
    1 * monitor.onSerialize(payloadSize)
    1 * monitor.onSend(2, payloadSize, response)
    1 * traceMapper.reset()
    0 * _

    cleanup:
    disruptor.close()

    where:
    trace = [newSpanOf(0, "fixed-thread-name")]
    response = DDAgentApi.Response.success(200)
    payloadSize = 1000
  }

  def "test agent error response"() {
    setup:
    def disruptor = new TraceProcessingDisruptor(
      2,
      monitor,
      api,
      -1,
      SECONDS,
      false)
    disruptor.start()

    when:
    disruptor.flush(1, SECONDS)

    then:
    0 * _

    when:
    assert disruptor.publish(trace, 1)
    assert disruptor.publish(trace, 1)
    assert disruptor.flush(1, SECONDS)

    then:
    1 * api.selectTraceMapper() >> traceMapper
    1 * traceMapper.messageBufferSize() >> (5 << 20)
    2 * traceMapper.map(trace, _)
    1 * traceMapper.newPayload() >> payload
    1 * payload.withRepresentativeCount(2) >> payload
    1 * payload.withBody(2, _) >> payload
    1 * payload.sizeInBytes() >> payloadSize
    1 * api.sendSerializedTraces(payload) >> response
    1 * monitor.onSerialize(payloadSize)
    1 * monitor.onFailedSend(2, payloadSize, response)
    1 * traceMapper.reset()
    0 * _

    cleanup:
    disruptor.close()

    where:
    trace = [newSpanOf(0, "fixed-thread-name")]
    response << [DDAgentApi.Response.failed(500), DDAgentApi.Response.failed(new Throwable())]
    payloadSize = 1000
  }

  def "test flood of traces"() {
    setup:
    def disruptor = new TraceProcessingDisruptor(
      disruptorSize,
      monitor,
      api,
      -1,
      SECONDS,
      false)
    disruptor.start()

    when:
    (1..traceCount).each {
      disruptor.publish(trace, 1)
    }
    disruptor.flush(1, SECONDS)

    then:
    1 * api.selectTraceMapper() >> traceMapper
    1 * traceMapper.messageBufferSize() >> (5 << 20)
    _ * traceMapper.map(trace, _)
    1 * traceMapper.newPayload() >> payload
    1 * payload.withRepresentativeCount({ it > 10 }) >> payload
    1 * payload.withBody({ it > 10 }, _) >> payload
    1 * payload.sizeInBytes() >> payloadSize
    1 * api.sendSerializedTraces(payload) >> response
    1 * monitor.onSerialize(payloadSize)
    1 * monitor.onSend({ it > 10 }, payloadSize, response)
    1 * traceMapper.reset()
    0 * _

    cleanup:
    disruptor.close()

    where:
    trace = [newSpanOf(0, "fixed-thread-name")]
    response = DDAgentApi.Response.success(200)
    disruptorSize = 10
    traceCount = 100 // Shouldn't trigger payload, but bigger than the disruptor size.
    payloadSize = 1000
  }

  def "test flush by time"() {
    setup:
    def disruptor = new TraceProcessingDisruptor(
      DISRUPTOR_BUFFER_SIZE,
      monitor,
      api,
      1,
      SECONDS,
      true)
    disruptor.start()

    when:
    (1..5).each {
      disruptor.publish(trace, 1)
    }
    phaser.awaitAdvanceInterruptibly(phaser.arriveAndDeregister())

    then:
    1 * api.selectTraceMapper() >> traceMapper
    1 * traceMapper.messageBufferSize() >> (5 << 20)
    5 * traceMapper.map(trace, _)
    1 * traceMapper.newPayload() >> payload
    1 * payload.withRepresentativeCount(5) >> payload
    1 * payload.withBody(5, _) >> payload
    1 * payload.sizeInBytes() >> payloadSize
    1 * api.sendSerializedTraces(payload) >> response
    1 * monitor.onSerialize(payloadSize)
    1 * monitor.onSend(_, _, _) >> {
      phaser.arrive()
    }
    1 * traceMapper.reset()
    0 * _

    cleanup:
    disruptor.close()

    where:
    span = newSpanOf(0, "fixed-thread-name")
    trace = (1..10).collect { span }
    response = DDAgentApi.Response.success(200)
    payloadSize = 1000
  }

  // not currently working
  @Ignore
  def "test default buffer size"() {
    setup:
    def disruptor = new TraceProcessingDisruptor(
      DISRUPTOR_BUFFER_SIZE,
      monitor,
      api,
      -1,
      SECONDS,
      false)
    disruptor.start()

    when:
    (0..maxedPayloadTraceCount).each {
      while (disruptor.getDisruptorRemainingCapacity() <= 1) {
//        println("waiting...")
        Thread.sleep(1)
        // Busywait because we don't want to fill up the ring buffer
      }
      disruptor.publish(minimalTrace, 1)
    }

    then:
//    1 * api.sendSerializedTraces(_, _, _) >> response
    1 * monitor.onSerialize({ it > 1000 })
//    1 * monitor.onSend(maxedPayloadTraceCount, _, response)
    0 * _

    when:
    disruptor.flush(1, SECONDS)

    then:
    1 * api.sendSerializedTraces({ it == 1 }, { it == 1 }, _) >> response
    1 * monitor.onSerialize({ it > 100 })
    1 * monitor.onSend(1, _, response)
    0 * _

    cleanup:
    disruptor.close()

    where:
    minimalTrace = [newSpanOf(0, "")]
    traceSize = calculateSize(minimalTrace)
    maxedPayloadTraceCount = ((int) (TraceProcessingDisruptor.DEFAULT_BUFFER_SIZE / traceSize))
    response = DDAgentApi.Response.success(200)
  }

  @Ignore
  // This doesn't seem to apply anymore.  (events can be added after close.)
  def "check that are no interactions after close"() {
    setup:
    def disruptor = new TraceProcessingDisruptor(
      DISRUPTOR_BUFFER_SIZE,
      monitor,
      api,
      1,
      SECONDS,
      true)
    disruptor.start()

    when:
    disruptor.close()
    disruptor.publish([], 1)
    disruptor.flush(1, SECONDS)

    then:
    0 * _
  }

  static int calculateSize(List<DDSpan> trace) {
    ByteBuffer buffer = ByteBuffer.allocate(1024)
    AtomicInteger size = new AtomicInteger()
    def packer = new Packer(new ByteBufferConsumer() {
      @Override
      void accept(int messageCount, ByteBuffer buffy) {
        size.set(buffy.limit() - buffy.position() - 1)
      }
    }, buffer)
    packer.format(trace, new TraceMapperV0_4())
    packer.flush()
    return size.get()
  }
}
