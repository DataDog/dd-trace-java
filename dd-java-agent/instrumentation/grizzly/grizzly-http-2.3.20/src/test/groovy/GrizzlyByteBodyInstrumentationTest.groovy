import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.gateway.Flow
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.http.StoredBodySupplier
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.bootstrap.instrumentation.api.TagContext
import datadog.trace.instrumentation.grizzlyhttp232.GrizzlyByteBodyInstrumentation
import org.glassfish.grizzly.Buffer
import org.glassfish.grizzly.attributes.AttributeHolder
import org.glassfish.grizzly.http.HttpHeader
import org.glassfish.grizzly.http.io.InputBuffer
import org.glassfish.grizzly.http.io.NIOInputStream
import org.glassfish.grizzly.memory.ByteBufferWrapper

import java.nio.ByteBuffer
import java.util.function.BiFunction

import static datadog.trace.api.gateway.Events.EVENTS

/**
 * @see GrizzlyByteBodyInstrumentation
 */
class GrizzlyByteBodyInstrumentationTest extends InstrumentationSpecification {
  NIOInputStream nioInputStream = Class.forName('org.glassfish.grizzly.http.server.NIOInputStreamImpl').newInstance()
  HttpHeader mockHttpHeader = Mock()
  InputBuffer mockInputBuffer = Mock()
  AttributeHolder attributeHolder = Mock()
  def scope
  def cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC)
  def ss = AgentTracer.get().getSubscriptionService(RequestContextSlot.APPSEC)
  def supplier
  boolean bodyDone

  // TODO: determine whether or not the field is final
  def prepare(String encoding = null) {
    _ * mockHttpHeader.attributes >> attributeHolder
    if (encoding) {
      1 * mockHttpHeader.getCharacterEncoding() >> encoding
    }
    1 * attributeHolder.setAttribute('datadog.intercepted_request_body', Boolean.TRUE)

    TagContext ctx = new TagContext().withRequestContextDataAppSec(new Object())
    def agentSpan = AgentTracer.startSpan('test-span', ctx)
    this.scope = AgentTracer.activateSpan(agentSpan)

    ss.registerCallback(EVENTS.requestBodyStart(), { RequestContext reqContext, StoredBodySupplier sup ->
      supplier = sup
      null
    } as BiFunction)
    ss.registerCallback(EVENTS.requestBodyDone(), { RequestContext reqContext, StoredBodySupplier sup ->
      bodyDone = true
      Flow.ResultFlow.empty()
    } as BiFunction)

    def httpHeaderField = InputBuffer.getDeclaredField('httpHeader')
    httpHeaderField.accessible = true
    httpHeaderField.set(mockInputBuffer, mockHttpHeader)

    nioInputStream.inputBuffer = mockInputBuffer
  }

  void cleanup() {
    cbp.reset()
    nioInputStream.recycle()
    this.scope.close()
  }

  void 'read — no args variant'() {
    prepare()

    when:
    assert nioInputStream.read() == (int) 'a'

    then:
    1 * mockInputBuffer.readByte() >> (int) 'a'
    collectedString == 'a'
    bodyDone == false
  }

  void 'read — array variant'() {
    setup:
    prepare()
    def bytes = new byte[5]

    when:
    assert nioInputStream.read(bytes) == 5

    then:
    1 * mockInputBuffer.read(bytes, 0, 5) >> {
      it[0][0] = (byte) 'h'
      it[0][1] = (byte) 'e'
      it[0][2] = (byte) 'l'
      it[0][3] = (byte) 'l'
      it[0][4] = (byte) 'o'
      5
    }
    collectedString == 'hello'
  }

  void 'read — array int int variant'() {
    setup:
    prepare()
    def bytes = new byte[7]

    when:
    assert nioInputStream.read(bytes, 1, 6) == 5

    then:
    1 * mockInputBuffer.read(bytes, 1, 6) >> {
      it[0][1] = (byte) 'h'
      it[0][2] = (byte) 'e'
      it[0][3] = (byte) 'l'
      it[0][4] = (byte) 'l'
      it[0][5] = (byte) 'o'
      5
    }
    collectedString == 'hello'
  }

  void 'read —  readBuffer variant no args'() {
    prepare()
    Buffer buffer

    when:
    buffer = nioInputStream.readBuffer()

    then:
    1 * mockInputBuffer.readBuffer() >> {
      def bb = ByteBuffer.allocate(7)
      bb.position(1)
      bb.put('hello' as byte[])
      bb.position(1)
      bb.limit(6)
      new ByteBufferWrapper(bb)
    }
    byte[] res = new byte[5]
    buffer.get(res)
    res == 'hello' as byte[]
    collectedString == 'hello'
  }

  void 'read —  readBuffer variant int'() {
    prepare()
    Buffer buffer

    when:
    buffer = nioInputStream.readBuffer(6)

    then:
    1 * mockInputBuffer.readBuffer(6) >> {
      def bb = ByteBuffer.allocate(7)
      bb.position(1)
      bb.put('hello' as byte[])
      bb.position(1)
      bb.limit(6)
      new ByteBufferWrapper(bb)
    }
    byte[] res = new byte[5]
    buffer.get(res)
    res == 'hello' as byte[]
    collectedString == 'hello'
  }

  void 'read end signalling — no args variant'() {
    prepare()

    when:
    assert nioInputStream.read() == -1

    then:
    1 * mockInputBuffer.readByte() >> -1
    bodyDone == true
  }

  void 'read end signalling — byte array variant'() {
    prepare()

    when:
    assert nioInputStream.read(new byte[1]) == -1

    then:
    1 * mockInputBuffer.read(_ as byte[], 0, 1) >> -1
    bodyDone == true
  }

  void 'read end signalling — byte array int int variant'() {
    prepare()

    when:
    assert nioInputStream.read(new byte[1], 0, 1) == -1

    then:
    1 * mockInputBuffer.read(_ as byte[], 0, 1) >> -1
    bodyDone == true
  }

  void 'read end signalling — isFinished'() {
    prepare()

    when:
    assert nioInputStream.finished

    then:
    1 * mockInputBuffer.finished >> true
    bodyDone == true
  }

  void 'read end signalling — isFinished negative test'() {
    prepare()

    when:
    assert !nioInputStream.finished

    then:
    1 * mockInputBuffer.finished >> false
    bodyDone == false
  }

  void 'encoding defaults to latin1'() {
    prepare()

    when:
    2.times { nioInputStream.read() }

    then:
    2 * mockInputBuffer.readByte() >>> [0xC3, 0xA1]
    collectedString == '\u00C3\u00A1'
  }

  void 'encoding is set to UTF8'() {
    prepare('UTF-8')

    when:
    2.times { nioInputStream.read() }

    then:
    2 * mockInputBuffer.readByte() >>> [0xC3, 0xA1]
    collectedString == 'á'
  }

  private String getCollectedString() {
    supplier.get() as String
  }
}
