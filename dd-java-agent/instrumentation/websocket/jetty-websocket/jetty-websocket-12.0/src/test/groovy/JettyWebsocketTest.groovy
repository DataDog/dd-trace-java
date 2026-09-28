import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.DDSpanTypes
import datadog.trace.core.DDSpan
import org.eclipse.jetty.util.Callback
import org.eclipse.jetty.websocket.api.UpgradeRequest
import org.eclipse.jetty.websocket.api.WebSocketContainer
import org.eclipse.jetty.websocket.common.JettyWebSocketFrameHandler
import org.eclipse.jetty.websocket.core.CloseStatus
import org.eclipse.jetty.websocket.core.Behavior
import org.eclipse.jetty.websocket.core.CoreSession
import org.eclipse.jetty.websocket.core.Frame
import org.eclipse.jetty.websocket.core.OpCode
import org.eclipse.jetty.websocket.core.WebSocketComponents
import org.eclipse.jetty.websocket.server.internal.ServerFrameHandlerFactory

import java.util.concurrent.ExecutionException

import static datadog.trace.agent.test.base.HttpServerTest.websocketCloseSpan
import static datadog.trace.agent.test.base.HttpServerTest.websocketReceiveSpan
import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_URL
import static java.util.concurrent.TimeUnit.SECONDS

class JettyWebsocketTest extends InstrumentationSpecification {

  def "test native jetty advices with endpoint class #endpoint.class and message type #msgType"() {
    setup:
    def url = "ws://inmemory/test/param"
    def frameHandler = createFrameHandler(endpoint)
    def messageCallback = new Callback.Completable()
    def closeCallback = new Callback.Completable()

    when:
    openFrameHandler(frameHandler)
    frameHandler.onFrame(new Frame(msgType == "text" ? OpCode.TEXT : OpCode.BINARY, "hello world"), messageCallback)
    messageCallback.get(5, SECONDS)
    frameHandler.onFrame(CloseStatus.toFrame(CloseStatus.NORMAL, "bye"), closeCallback)
    closeCallback.get(5, SECONDS)

    then:
    endpoint.session != null
    endpoint.messages == ["hello world"]
    endpoint.messageSpans*.operationName*.toString() == ["websocket.receive"]
    activeSpan() == null
    endpoint.closeCode == 1000
    endpoint.closeReason == "bye"
    assertTraces(3) {
      DDSpan handshake
      trace(1) {
        handshake = span(0)
        basicSpan(it, "parent", "/test/param", null, null, [(HTTP_URL): url])
      }
      trace(1) {
        websocketReceiveSpan(it, handshake, msgType, 11)
      }
      trace(1) {
        websocketCloseSpan(it, handshake, false, 1000, "bye")
      }
    }

    where:
    endpoint                                  | msgType
    new JettyEndpoints.FullListener()         | "text"
    new JettyEndpoints.FullListener()         | "binary"
    new JettyEndpoints.PartialListener()      | "text"
    new JettyEndpoints.PartialListener()      | "binary"
    new JettyEndpoints.PojoFullEndpoint()     | "text"
    new JettyEndpoints.PojoFullEndpoint()     | "binary"
    new JettyEndpoints.PojoPartialEndpoint()  | "text"
    new JettyEndpoints.PojoPartialEndpoint()  | "binary"
  }

  def "fragmented #msgType messages share a span for #endpoint.class"() {
    setup:
    def frameHandler = createFrameHandler(endpoint)
    openFrameHandler(frameHandler)
    def opcode = msgType == "text" ? OpCode.TEXT : OpCode.BINARY

    when:
    deliver(frameHandler, new Frame(opcode, "hello ").setFin(false))

    then:
    activeSpan() == null
    endpoint.messages == ["hello "]
    TEST_WRITER.size() == 1

    when:
    deliver(frameHandler, new Frame(OpCode.CONTINUATION, "world"))
    deliver(frameHandler, new Frame(opcode, "again"))

    then:
    endpoint.messages == ["hello ", "world", "again"]
    endpoint.finalFragments == [false, true, true]
    endpoint.messageSpans[0].is(endpoint.messageSpans[1])
    !endpoint.messageSpans[1].is(endpoint.messageSpans[2])
    activeSpan() == null
    assertTraces(3) {
      DDSpan handshake
      trace(1) {
        handshake = span(0)
        basicSpan(it, "parent", "/test/param", null, null, [(HTTP_URL): "ws://inmemory/test/param"])
      }
      trace(1) {
        websocketReceiveSpan(it, handshake, msgType, 11, 2)
      }
      trace(1) {
        websocketReceiveSpan(it, handshake, msgType, 5)
      }
    }

    where:
    endpoint                                 | msgType
    new JettyEndpoints.PartialListener()      | "text"
    new JettyEndpoints.PartialListener()      | "binary"
    new JettyEndpoints.PojoPartialEndpoint()  | "text"
    new JettyEndpoints.PojoPartialEndpoint()  | "binary"
  }

  def "handler failure marks #operation as errored and closes the scope"() {
    setup:
    def frameHandler = createFrameHandler(endpoint)
    openFrameHandler(frameHandler)

    when:
    deliver(frameHandler, frame)

    then:
    def error = thrown(ExecutionException)
    error.cause != null
    activeSpan() == null
    assertTraces(2) {
      trace(1) {
        basicSpan(it, "parent", "/test/param", null, null, [(HTTP_URL): "ws://inmemory/test/param"])
      }
      trace(1) {
        span {
          operationName operation
          resourceName "websocket /test/param"
          spanType DDSpanTypes.WEBSOCKET
          errored true
          ignoreSpanLinks()
          tags(false) {
            errorTags(IllegalStateException, "handler failed")
          }
        }
      }
    }

    where:
    endpoint                                                | frame                                        | operation
    new JettyEndpoints.FullListener(failMessages: true)      | new Frame(OpCode.TEXT, "hello")               | "websocket.receive"
    new JettyEndpoints.FullListener(failMessages: true)      | new Frame(OpCode.BINARY, "hello")             | "websocket.receive"
    new JettyEndpoints.PartialListener(failMessages: true)   | new Frame(OpCode.TEXT, "hello").setFin(false) | "websocket.receive"
    new JettyEndpoints.PojoFullEndpoint(failClose: true)     | CloseStatus.toFrame(CloseStatus.NORMAL, "bye") | "websocket.close"
  }

  def "does not trace native messages for #behavior with handshake tracing #traced"() {
    setup:
    def endpoint = new JettyEndpoints.FullListener()
    def frameHandler = createFrameHandler(endpoint)

    when:
    openFrameHandler(frameHandler, behavior, traced)
    deliver(frameHandler, new Frame(OpCode.TEXT, "hello"))
    deliver(frameHandler, CloseStatus.toFrame(CloseStatus.NORMAL, "bye"))

    then:
    endpoint.messages == ["hello"]
    endpoint.messageSpans == [null]
    endpoint.closeCode == 1000
    activeSpan() == null
    assertTraces(traced ? 1 : 0) {
      if (traced) {
        trace(1) {
          basicSpan(it, "parent", "/test/param", null, null, [(HTTP_URL): "ws://inmemory/test/param"])
        }
      }
    }

    where:
    behavior        | traced
    Behavior.SERVER | false
    Behavior.CLIENT | true
  }

  def "binary callback failure marks the receive span for #endpoint.class"() {
    setup:
    endpoint.failCallback = true
    def frameHandler = createFrameHandler(endpoint)
    openFrameHandler(frameHandler)

    when:
    deliver(frameHandler, new Frame(OpCode.BINARY, "hello").setFin(last))

    then:
    def error = thrown(ExecutionException)
    error.cause.message == "callback failed"
    activeSpan() == null
    TEST_WRITER.waitForTraces(2)
    endpoint.messageSpans[0].error
    endpoint.messageSpans[0].getTag("error.message") == "callback failed"

    where:
    endpoint                                | last
    new JettyEndpoints.FullListener()        | true
    new JettyEndpoints.PartialListener()     | true
    new JettyEndpoints.PartialListener()     | false
    new JettyEndpoints.PojoFullEndpoint()    | true
    new JettyEndpoints.PojoPartialEndpoint() | false
  }

  def "deferred binary callback completes the receive span with failure #fail"() {
    setup:
    def endpoint = new JettyEndpoints.FullListener(deferCallback: true)
    def frameHandler = createFrameHandler(endpoint)
    def callback = new Callback.Completable()
    openFrameHandler(frameHandler)

    when:
    frameHandler.onFrame(new Frame(OpCode.BINARY, "hello"), callback)

    then:
    endpoint.pendingCallback != null
    !callback.done
    TEST_WRITER.size() == 1
    activeSpan() == null

    when:
    def completion = java.util.concurrent.CompletableFuture.runAsync {
      if (fail) {
        endpoint.pendingCallback.fail(new IllegalStateException("callback failed"))
      } else {
        endpoint.pendingCallback.succeed()
      }
      assert activeSpan() == null
    }
    completion.get(5, SECONDS)

    then:
    callback.done
    callback.completedExceptionally == fail
    TEST_WRITER.waitForTraces(2)
    endpoint.messageSpans[0].isError() == fail
    activeSpan() == null

    where:
    fail << [false, true]
  }

  def "pending binary messages have independent spans for #endpoint.class with reverse completion #reverse"() {
    setup:
    endpoint.deferCallback = true
    def frameHandler = createFrameHandler(endpoint)
    openFrameHandler(frameHandler)
    def callbacks = [new Callback.Completable(), new Callback.Completable()]
    frameHandler.onFrame(new Frame(OpCode.BINARY, "hello"), callbacks[0])
    def first = endpoint.pendingCallback
    frameHandler.onFrame(new Frame(OpCode.BINARY, "again"), callbacks[1])
    def second = endpoint.pendingCallback

    expect:
    !endpoint.messageSpans[0].is(endpoint.messageSpans[1])
    callbacks.every { !it.done }
    TEST_WRITER.size() == 1
    activeSpan() == null

    when:
    if (reverse) {
      second.fail(new IllegalStateException("second callback failed"))
    } else {
      first.succeed()
    }

    then:
    TEST_WRITER.waitForTraces(2)
    TEST_WRITER[1][0].is(endpoint.messageSpans[reverse ? 1 : 0])
    !callbacks[reverse ? 0 : 1].done

    when:
    if (reverse) {
      first.succeed()
    } else {
      second.fail(new IllegalStateException("second callback failed"))
    }

    then:
    TEST_WRITER.waitForTraces(3)
    TEST_WRITER[2][0].is(endpoint.messageSpans[reverse ? 0 : 1])
    !endpoint.messageSpans[0].isError()
    endpoint.messageSpans[1].isError()
    endpoint.messageSpans[1].getTag("error.message") == "second callback failed"
    callbacks[0].done && !callbacks[0].completedExceptionally
    callbacks[1].completedExceptionally
    activeSpan() == null

    where:
    endpoint                                | reverse
    new JettyEndpoints.FullListener()        | false
    new JettyEndpoints.FullListener()        | true
    new JettyEndpoints.PartialListener()     | false
    new JettyEndpoints.PartialListener()     | true
    new JettyEndpoints.PojoFullEndpoint()    | false
    new JettyEndpoints.PojoFullEndpoint()    | true
    new JettyEndpoints.PojoPartialEndpoint() | false
    new JettyEndpoints.PojoPartialEndpoint() | true
  }

  def "partial binary message waits for earlier fragment callbacks with failure #fail"() {
    setup:
    def endpoint = new JettyEndpoints.PartialListener(deferCallback: true)
    def frameHandler = createFrameHandler(endpoint)
    openFrameHandler(frameHandler)
    def first = new Callback.Completable()
    def last = new Callback.Completable()
    frameHandler.onFrame(new Frame(OpCode.BINARY, "hello ").setFin(false), first)
    def firstCallback = endpoint.pendingCallback
    frameHandler.onFrame(new Frame(OpCode.CONTINUATION, "world"), last)

    when:
    endpoint.pendingCallback.succeed()
    last.get(5, SECONDS)

    then:
    !first.done
    TEST_WRITER.size() == 1
    endpoint.messageSpans[0].is(endpoint.messageSpans[1])

    when:
    if (fail) {
      firstCallback.fail(new IllegalStateException("first fragment failed"))
    } else {
      firstCallback.succeed()
    }

    then:
    first.done
    first.completedExceptionally == fail
    TEST_WRITER.waitForTraces(2)
    endpoint.messageSpans[0].isError() == fail
    if (fail) {
      assert endpoint.messageSpans[0].getTag("error.message") == "first fragment failed"
    }
    endpoint.messageSpans[0].getTag("websocket.message.length") == 11
    endpoint.messageSpans[0].getTag("websocket.message.frames") == 2
    activeSpan() == null

    where:
    fail << [false, true]
  }

  def "handler failure after callback success still marks the receive span"() {
    setup:
    def endpoint = new JettyEndpoints.FullListener(failAfterCallback: true)
    def frameHandler = createFrameHandler(endpoint)
    openFrameHandler(frameHandler)

    when:
    frameHandler.onFrame(new Frame(OpCode.BINARY, "hello"), new Callback.Completable())

    then:
    TEST_WRITER.waitForTraces(2)
    endpoint.messageSpans[0].error
    endpoint.messageSpans[0].getTag("error.message") == "handler failed"
    activeSpan() == null
  }

  def "termination finishes a fragmented #msgType receive span with close frame #closeFrame and close handler #closeHandler"() {
    setup:
    def endpoint = closeHandler ? new JettyEndpoints.PartialListener() : new JettyEndpoints.NoCloseEndpoint()
    def frameHandler = createFrameHandler(endpoint)
    openFrameHandler(frameHandler)
    deliver(frameHandler, new Frame(msgType == "text" ? OpCode.TEXT : OpCode.BINARY, "hello").setFin(false))
    assert TEST_WRITER.size() == 1

    when:
    if (closeFrame) {
      deliver(frameHandler, CloseStatus.toFrame(CloseStatus.NORMAL, "bye"))
    } else {
      def callback = new Callback.Completable()
      frameHandler.onClosed(new CloseStatus(CloseStatus.NORMAL, "bye"), callback)
      callback.get(5, SECONDS)
    }

    then:
    activeSpan() == null
    assertTraces(closeHandler ? 3 : 2) {
      DDSpan handshake
      trace(1) {
        handshake = span(0)
        basicSpan(it, "parent", "/test/param", null, null, [(HTTP_URL): "ws://inmemory/test/param"])
      }
      trace(1) {
        websocketReceiveSpan(it, handshake, msgType, 5)
      }
      if (closeHandler) {
        trace(1) {
          websocketCloseSpan(it, handshake, false, 1000, "bye")
        }
      }
    }

    where:
    msgType  | closeFrame | closeHandler
    "text"   | true       | true
    "binary" | true       | true
    "text"   | false      | true
    "binary" | false      | true
    "text"   | true       | false
    "binary" | true       | false
    "text"   | false      | false
    "binary" | false      | false
  }

  def "deferred partial binary success keeps the span open until the final callback"() {
    setup:
    def endpoint = new JettyEndpoints.PartialListener(deferCallback: true)
    def frameHandler = createFrameHandler(endpoint)
    openFrameHandler(frameHandler)
    def first = new Callback.Completable()
    def last = new Callback.Completable()

    when:
    frameHandler.onFrame(new Frame(OpCode.BINARY, "hello ").setFin(false), first)
    endpoint.pendingCallback.succeed()
    first.get(5, SECONDS)
    frameHandler.onFrame(new Frame(OpCode.CONTINUATION, "world"), last)

    then:
    !last.done
    TEST_WRITER.size() == 1
    endpoint.messageSpans[0].is(endpoint.messageSpans[1])

    when:
    endpoint.pendingCallback.succeed()
    last.get(5, SECONDS)

    then:
    assertTraces(2) {
      DDSpan handshake
      trace(1) {
        handshake = span(0)
        basicSpan(it, "parent", "/test/param", null, null, [(HTTP_URL): "ws://inmemory/test/param"])
      }
      trace(1) {
        websocketReceiveSpan(it, handshake, "binary", 11, 2)
      }
    }
    activeSpan() == null
  }

  def "termination finishes all pending binary messages with final fragment #last"() {
    setup:
    def endpoint = new JettyEndpoints.PartialListener(deferCallback: true)
    def frameHandler = createFrameHandler(endpoint)
    openFrameHandler(frameHandler)
    def previousMessage = new Callback.Completable()
    frameHandler.onFrame(new Frame(OpCode.BINARY, "previous"), previousMessage)
    def previousCallback = endpoint.pendingCallback
    def message = new Callback.Completable()
    frameHandler.onFrame(new Frame(OpCode.BINARY, "hello").setFin(last), message)

    expect:
    TEST_WRITER.size() == 1
    !endpoint.messageSpans[0].is(endpoint.messageSpans[1])

    when:
    def closed = new Callback.Completable()
    frameHandler.onClosed(new CloseStatus(CloseStatus.NORMAL, "bye"), closed)
    closed.get(5, SECONDS)

    then:
    TEST_WRITER.waitForTraces(4)
    TEST_WRITER.flatten().containsAll(endpoint.messageSpans)

    when:
    endpoint.pendingCallback.succeed()
    message.get(5, SECONDS)
    previousCallback.succeed()
    previousMessage.get(5, SECONDS)

    then:
    TEST_WRITER.size() == 4
    activeSpan() == null

    where:
    last << [false, true]
  }

  private JettyWebSocketFrameHandler createFrameHandler(Object endpoint) {
    def factory = new ServerFrameHandlerFactory(Stub(WebSocketContainer), new WebSocketComponents())
    def frameHandler = factory.newJettyFrameHandler(endpoint)
    frameHandler.setUpgradeRequest(Stub(UpgradeRequest) {
      getRequestURI() >> URI.create("ws://inmemory/test/param")
    })
    return frameHandler
  }

  private void openFrameHandler(JettyWebSocketFrameHandler frameHandler, Behavior connectionBehavior = Behavior.SERVER, boolean traced = true) {
    def session = new CoreSession.Empty() {
        @Override
        Behavior getBehavior() {
          return connectionBehavior
        }
      }
    def openCallback = new Callback.Completable()
    if (traced) {
      runUnderTrace("parent") {
        activeSpan().setTag(HTTP_URL, "ws://inmemory/test/param")
        frameHandler.onOpen(session, openCallback)
        openCallback.get(5, SECONDS)
      }
    } else {
      frameHandler.onOpen(session, openCallback)
      openCallback.get(5, SECONDS)
    }
  }

  private static void deliver(JettyWebSocketFrameHandler frameHandler, Frame frame) {
    def callback = new Callback.Completable()
    frameHandler.onFrame(frame, callback)
    callback.get(5, SECONDS)
  }
}
