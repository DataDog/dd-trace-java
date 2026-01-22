import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.gateway.Flow
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.http.StoredBodySupplier
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.bootstrap.instrumentation.api.TagContext
import datadog.trace.instrumentation.grizzlyhttp232.GrizzlyCharBodyInstrumentation
import org.glassfish.grizzly.attributes.AttributeHolder
import org.glassfish.grizzly.http.HttpHeader
import org.glassfish.grizzly.http.io.InputBuffer
import org.glassfish.grizzly.http.io.NIOReader

import java.nio.CharBuffer
import java.util.function.BiFunction

import static datadog.trace.api.gateway.Events.EVENTS

/**
 * @see GrizzlyCharBodyInstrumentation
 */
class GrizzlyCharBodyInstrumentationTest extends InstrumentationSpecification {
  NIOReader nioReader = Class.forName('org.glassfish.grizzly.http.server.NIOReaderImpl').newInstance()
  HttpHeader mockHttpHeader = Mock()
  InputBuffer mockInputBuffer = Mock()
  AttributeHolder attributeHolder = Mock()
  def scope
  def ss = AgentTracer.get().getSubscriptionService(RequestContextSlot.APPSEC)
  def supplier
  boolean bodyDone

  // TODO: determine whether or not the field is final
  def setup() {
    _ * mockHttpHeader.attributes >> attributeHolder
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

    nioReader.inputBuffer = mockInputBuffer
  }

  void cleanup() {
    ss.reset()
    nioReader.recycle()
    this.scope.close()
  }

  void 'read — no args variant'() {
    when:
    assert nioReader.read() == (int) 'a'

    then:
    1 * mockInputBuffer.readChar() >> (int) 'a'
    collectedString == 'a'
    bodyDone == false
  }

  void 'read — array variant'() {
    setup:
    def chars = new char[5]

    when:
    assert nioReader.read(chars) == 5

    then:
    1 * mockInputBuffer.read(chars, 0, 5) >> {
      it[0][0] = (char) 'h'
      it[0][1] = (char) 'é'
      it[0][2] = (char) 'l'
      it[0][3] = (char) 'l'
      it[0][4] = (char) 'o'
      5
    }
    collectedString == 'héllo'
  }

  void 'read — array int int variant'() {
    setup:
    def chars = new char[7]

    when:
    assert nioReader.read(chars, 1, 6) == 5

    then:
    1 * mockInputBuffer.read(chars, 1, 6) >> {
      it[0][1] = (char) 'h'
      it[0][2] = (char) 'é'
      it[0][3] = (char) 'l'
      it[0][4] = (char) 'l'
      it[0][5] = (char) 'o'
      5
    }
    collectedString == 'héllo'
  }

  void 'read —  CharBuffer variant'() {
    setup:
    CharBuffer cb = CharBuffer.allocate(7)
    cb.position(1)
    cb.limit(6)

    when:
    assert nioReader.read(cb) == 5

    then:
    1 * mockInputBuffer.read(_ as CharBuffer) >> {
      cb.put('h' as char)
      cb.put('é' as char)
      cb.put('l' as char)
      cb.put('l' as char)
      cb.put('o' as char)
      5
    }
    cb.position() == 6
    cb.limit() == 6
    collectedString == 'héllo'

    when:
    cb.position(1)
    char[] res = new char[5]
    cb.get(res)

    then:
    res == 'héllo' as char[]
  }

  void 'read end signalling — no args variant'() {
    when:
    assert nioReader.read() == -1

    then:
    1 * mockInputBuffer.readChar() >> -1
    bodyDone == true
  }

  void 'read end signalling — char array variant'() {
    when:
    assert nioReader.read(new char[1]) == -1

    then:
    1 * mockInputBuffer.read(_ as char[], 0, 1) >> -1
    bodyDone == true
  }

  void 'read end signalling — char array int int variant'() {
    when:
    assert nioReader.read(new char[1], 0, 1) == -1

    then:
    1 * mockInputBuffer.read(_ as char[], 0, 1) >> -1
    bodyDone == true
  }

  void 'read end signalling — CharBuffer variant'() {
    when:
    assert nioReader.read(CharBuffer.allocate(1)) == -1

    then:
    1 * mockInputBuffer.read(_ as CharBuffer) >> -1
    bodyDone == true
  }

  void 'read end signalling — isFinished'() {
    when:
    assert nioReader.finished

    then:
    1 * mockInputBuffer.finished >> true
    bodyDone == true
  }

  void 'read end signalling — isFinished negative test'() {
    when:
    assert !nioReader.finished

    then:
    1 * mockInputBuffer.finished >> false
    bodyDone == false
  }

  private String getCollectedString() {
    supplier.get() as String
  }
}
