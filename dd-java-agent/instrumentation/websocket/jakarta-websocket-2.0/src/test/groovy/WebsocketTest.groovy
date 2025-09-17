import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.DDTags
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.DDSpan
import jakarta.websocket.ClientEndpointConfig
import jakarta.websocket.CloseReason
import jakarta.websocket.ContainerProvider
import jakarta.websocket.Endpoint
import jakarta.websocket.server.ServerApplicationConfig
import jakarta.websocket.server.ServerEndpointConfig
import net.bytebuddy.utility.RandomString
import org.glassfish.tyrus.container.inmemory.InMemoryClientContainer
import org.glassfish.tyrus.server.TyrusServerConfiguration

import java.nio.ByteBuffer

import static datadog.trace.agent.test.base.HttpServerTest.someBytes
import static datadog.trace.agent.test.base.HttpServerTest.websocketCloseSpan
import static datadog.trace.agent.test.base.HttpServerTest.websocketReceiveSpan
import static datadog.trace.agent.test.base.HttpServerTest.websocketSendSpan
import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_CLASSES_EXCLUDE
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_WEBSOCKET_MESSAGES_ENABLED
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_WEBSOCKET_MESSAGES_INHERIT_SAMPLING
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_WEBSOCKET_MESSAGES_SEPARATE_TRACES
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_WEBSOCKET_TAG_SESSION_ID
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan

class WebsocketTest extends InstrumentationSpecification {

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig(TRACE_WEBSOCKET_MESSAGES_ENABLED, "true")
    injectSysConfig(TRACE_CLASSES_EXCLUDE, "EndpointWrapper")
  }

  def createHandshakeSpan(String spanName, String url) {
    def span = TEST_TRACER.startSpan("test", spanName, null)
    handshakeTags(url).each { span.setTag(it.key, it.value) }
    span.finish()
    span
  }

  def deployEndpointAndConnect(Endpoint endpoint, Object handshakeClientSpan, Object handshakeServerSpan, String url) {
    def webSocketContainer = ContainerProvider.getWebSocketContainer()
    def sec = ServerEndpointConfig.Builder.create(EndpointWrapper, "/test")
    .encoders([Endpoints.CustomMessageEncoder])
    .decoders([Endpoints.CustomMessageDecoder])
    .build()

    sec.getUserProperties().put(Endpoint.name, endpoint)
    sec.getUserProperties().put(AgentSpan.name, handshakeServerSpan)

    final ServerApplicationConfig serverConfig =
    new TyrusServerConfiguration(Collections.singleton(EndpointWrapper),
    Collections.singleton(sec))

    ClientEndpointConfig cec = ClientEndpointConfig.Builder.create()
    .encoders([Endpoints.CustomMessageEncoder])
    .decoders([Endpoints.CustomMessageDecoder])
    .build()
    cec.getUserProperties().put(InMemoryClientContainer.SERVER_CONFIG, serverConfig)

    try (def ignored = handshakeClientSpan != null ? activateSpan(handshakeClientSpan as AgentSpan) : null) {
      def session = webSocketContainer.connectToServer(new Endpoints.ClientTestEndpoint(), cec, URI.create(url))
      session
    }
  }

  def handshakeTags(url) {
    [(Tags.HTTP_METHOD): "GET", (Tags.HTTP_URL): url]
  }

  def "test full sync send and receive for endpoint #endpoint.class with #sendSize len #msgType message"() {
    when:
    String url = "ws://inmemory/test"
    def session = deployEndpointAndConnect(new Endpoints.TestEndpoint(endpoint),
    createHandshakeSpan("http.request", url),  //simulate client span
    createHandshakeSpan("servlet.request", url), // simulate server span
    url)

    runUnderTrace("parent") {
      if (msgType == "text") {
        session.getBasicRemote().sendText(message as String)
      } else {
        session.getBasicRemote().sendBinary(ByteBuffer.wrap(message as byte[]))
      }
      session.close()
    }
    then:
    assertTraces(5, {
      DDSpan serverHandshake, clientHandshake
      trace(1) {
        basicSpan(it, "http.request", "GET /test", null, null, handshakeTags(url))
        clientHandshake = span(0)
      }
      trace(1) {
        basicSpan(it, "servlet.request", "GET /test", null, null, handshakeTags(url))
        serverHandshake = span(0)
      }
      trace(3) {
        sortSpansByStart()
        basicSpan(it, "parent")
        websocketSendSpan(it, clientHandshake, msgType, sendSize, 1, span(0))
        websocketCloseSpan(it, clientHandshake, true, 1000, null, span(0))
      }

      trace(1) {
        websocketReceiveSpan(it, serverHandshake, msgType, rcvSize, 1)
      }
      trace(1) {
        websocketCloseSpan(it, serverHandshake, false, 1000, { it == null || it == 'no reason given' })
      }
    })
    where:
    endpoint                              | message               | msgType  | sendSize | rcvSize
    // text full
    new Endpoints.FullStringHandler()     | RandomString.make(10) | "text"   | 10       | 10
    new Endpoints.FullReaderHandler()     | RandomString.make(20) | "text"   | 20       | 0
    // binary full
    new Endpoints.FullByteBufferHandler() | someBytes(10)         | "binary" | 10       | 10
    new Endpoints.FullBytesHandler()      | someBytes(25)         | "binary" | 25       | 25
    new Endpoints.FullStreamHandler()     | someBytes(10)         | "binary" | 10       | 0
  }

  def "test partial sync send and receive for endpoint #endpoint.class with #chunks chunks and #size len #msgType message"() {
    when:
    String url = "ws://inmemory/test"
    def session = deployEndpointAndConnect(new Endpoints.TestEndpoint(endpoint),
    createHandshakeSpan("http.request", url),  //simulate client span
    createHandshakeSpan("servlet.request", url), // simulate server span
    url)

    runUnderTrace("parent") {
      def remote = session.getBasicRemote()
      if (msgType == "text") {
        for (int i = 0; i < message.size(); i++) {
          remote.sendText(message[i] as String, (i == message.size() - 1))
        }
      } else {
        for (int i = 0; i < message.size(); i++) {
          remote.sendBinary(ByteBuffer.wrap(message[i] as byte[]), i == (message.size() - 1))
        }
      }
      session.close()
    }
    then:
    assertTraces(5, {
      DDSpan serverHandshake, clientHandshake
      trace(1) {
        basicSpan(it, "http.request", "GET /test", null, null, handshakeTags(url))
        clientHandshake = span(0)
      }
      trace(1) {
        basicSpan(it, "servlet.request", "GET /test", null, null, handshakeTags(url))
        serverHandshake = span(0)
      }
      trace(3) {
        sortSpansByStart()
        basicSpan(it, "parent")
        websocketSendSpan(it, clientHandshake, msgType, size, chunks, span(0))
        websocketCloseSpan(it, clientHandshake, true, 1000, null, span(0))
      }
      trace(1) {
        websocketReceiveSpan(it, serverHandshake, msgType, size, chunks)
      }
      trace(1) {
        websocketCloseSpan(it, serverHandshake, false, 1000, { it == null || it == 'no reason given' })
      }
    })
    where:
    endpoint                                 | message                                        | msgType  | chunks | size
    // text partial
    new Endpoints.PartialStringHandler()     | [RandomString.make(10)]                        | "text"   | 1      | 10
    new Endpoints.PartialStringHandler()     | [RandomString.make(10), RandomString.make(10)] | "text"   | 2      | 20
    // binary Partial
    new Endpoints.PartialByteBufferHandler() | [someBytes(10)]                                | "binary" | 1      | 10
    new Endpoints.PartialByteBufferHandler() | [someBytes(10), someBytes(15)]                 | "binary" | 2      | 25
    new Endpoints.PartialBytesHandler()      | [someBytes(10)]                                | "binary" | 1      | 10
    new Endpoints.PartialBytesHandler()      | [someBytes(10), someBytes(15)]                 | "binary" | 2      | 25
  }

  def "test stream sync send and receive for endpoint #endpoint.class with #chunks chunks and #size len #msgType message"() {
    when:
    String url = "ws://inmemory/test"
    def session = deployEndpointAndConnect(new Endpoints.TestEndpoint(endpoint),
    createHandshakeSpan("http.request", url),  //simulate client span
    createHandshakeSpan("servlet.request", url), // simulate server span
    url)

    runUnderTrace("parent") {
      if (msgType == "text") {
        // tyrus text writer send the flushes when closing so the fin bit is sent along with the last message
        try (def writer = session.getBasicRemote().getSendWriter()) {
          message.each {
            writer.write(it as String)
          }
        }
      } else {
        // tyrus binary writer send the fin bit with an empty frame when close is called
        try (def os = session.getBasicRemote().getSendStream()) {
          message.each {
            os.write(it as byte[])
          }
        }
      }
      session.close()
    }
    then:
    assertTraces(5, {
      DDSpan serverHandshake, clientHandshake
      trace(1) {
        basicSpan(it, "http.request", "GET /test", null, null, handshakeTags(url))
        clientHandshake = span(0)
      }
      trace(1) {
        basicSpan(it, "servlet.request", "GET /test", null, null, handshakeTags(url))
        serverHandshake = span(0)
      }
      trace(3) {
        sortSpansByStart()
        basicSpan(it, "parent")
        websocketSendSpan(it, clientHandshake, msgType, size, chunks, span(0))
        websocketCloseSpan(it, clientHandshake, true, 1000, null, span(0))
      }
      trace(1) {
        websocketReceiveSpan(it, serverHandshake, msgType, size, chunks)
      }
      trace(1) {
        websocketCloseSpan(it, serverHandshake, false, 1000, { it == null || it == 'no reason given' })
      }
    })
    where:
    endpoint                                 | message                                        | msgType  | chunks | size
    // text partial
    new Endpoints.PartialStringHandler()     | [RandomString.make(10)]                        | "text"   | 1      | 10
    new Endpoints.PartialStringHandler()     | [RandomString.make(10), RandomString.make(15)] | "text"   | 2      | 25
    // binary Partial
    new Endpoints.PartialByteBufferHandler() | [someBytes(10)]                                | "binary" | 1      | 10
    new Endpoints.PartialByteBufferHandler() | [someBytes(10), someBytes(15)]                 | "binary" | 2      | 25
    new Endpoints.PartialBytesHandler()      | [someBytes(10)]                                | "binary" | 1      | 10
    new Endpoints.PartialBytesHandler()      | [someBytes(10), someBytes(15)]                 | "binary" | 2      | 25
  }

  def "test full async (future) send and receive for endpoint #endpoint.class with #sendSize len #msgType message"() {
    when:
    String url = "ws://inmemory/test"
    def session = deployEndpointAndConnect(new Endpoints.TestEndpoint(endpoint),
    createHandshakeSpan("http.request", url),  //simulate client span
    createHandshakeSpan("servlet.request", url), // simulate server span
    url)

    runUnderTrace("parent") {
      if (msgType == "text") {
        session.getAsyncRemote().sendText(message as String)
      } else {
        session.getAsyncRemote().sendBinary(ByteBuffer.wrap(message as byte[]))
      }
      session.close()
    }
    then:
    assertTraces(5, {
      DDSpan serverHandshake, clientHandshake
      trace(1) {
        basicSpan(it, "http.request", "GET /test", null, null, handshakeTags(url))
        clientHandshake = span(0)
      }
      trace(1) {
        basicSpan(it, "servlet.request", "GET /test", null, null, handshakeTags(url))
        serverHandshake = span(0)
      }
      trace(3) {
        sortSpansByStart()
        basicSpan(it, "parent")
        websocketSendSpan(it, clientHandshake, msgType, sendSize, 1, span(0))
        websocketCloseSpan(it, clientHandshake, true, 1000, null, span(0))
      }

      trace(1) {
        websocketReceiveSpan(it, serverHandshake, msgType, rcvSize, 1)
      }
      trace(1) {
        websocketCloseSpan(it, serverHandshake, false, 1000, { it == null || it == 'no reason given' })
      }
    })
    where:
    endpoint                              | message               | msgType  | sendSize | rcvSize
    // text full
    new Endpoints.FullStringHandler()     | RandomString.make(10) | "text"   | 10       | 10
    new Endpoints.FullReaderHandler()     | RandomString.make(20) | "text"   | 20       | 0
    // binary full
    new Endpoints.FullByteBufferHandler() | someBytes(10)         | "binary" | 10       | 10
    new Endpoints.FullBytesHandler()      | someBytes(25)         | "binary" | 25       | 25
    new Endpoints.FullStreamHandler()     | someBytes(10)         | "binary" | 10       | 0
  }

  def "test full async (SendHandler) send and receive for endpoint #endpoint.class with #sendSize len #msgType message"() {
    when:
    String url = "ws://inmemory/test"
    def session = deployEndpointAndConnect(new Endpoints.TestEndpoint(endpoint),
    createHandshakeSpan("http.request", url),  //simulate client span
    createHandshakeSpan("servlet.request", url), // simulate server span
    url)

    runUnderTrace("parent") {
      if (msgType == "text") {
        session.getAsyncRemote().sendText(message as String, { assert it.OK })
      } else {
        session.getAsyncRemote().sendBinary(ByteBuffer.wrap(message as byte[]), { assert it.OK })
      }
      session.close()
    }
    then:
    assertTraces(5, {
      DDSpan serverHandshake, clientHandshake
      trace(1) {
        basicSpan(it, "http.request", "GET /test", null, null, handshakeTags(url))
        clientHandshake = span(0)
      }
      trace(1) {
        basicSpan(it, "servlet.request", "GET /test", null, null, handshakeTags(url))
        serverHandshake = span(0)
      }
      trace(3) {
        sortSpansByStart()
        basicSpan(it, "parent")
        websocketSendSpan(it, clientHandshake, msgType, sendSize, 1, span(0))
        websocketCloseSpan(it, clientHandshake, true, 1000, null, span(0))
      }

      trace(1) {
        websocketReceiveSpan(it, serverHandshake, msgType, rcvSize, 1)
      }
      trace(1) {
        websocketCloseSpan(it, serverHandshake, false, 1000, { it == null || it == 'no reason given' })
      }
    })
    where:
    endpoint                              | message               | msgType  | sendSize | rcvSize
    // text full
    new Endpoints.FullStringHandler()     | RandomString.make(10) | "text"   | 10       | 10
    new Endpoints.FullReaderHandler()     | RandomString.make(20) | "text"   | 20       | 0
    // binary full
    new Endpoints.FullByteBufferHandler() | someBytes(10)         | "binary" | 10       | 10
    new Endpoints.FullBytesHandler()      | someBytes(25)         | "binary" | 25       | 25
    new Endpoints.FullStreamHandler()     | someBytes(10)         | "binary" | 10       | 0
  }

  def "test session close code #code and reason #reason"() {
    when:
    String url = "ws://inmemory/test"
    def session = deployEndpointAndConnect(new Endpoints.TestEndpoint(new Endpoints.FullStringHandler()),
    createHandshakeSpan("http.request", url),  //simulate client span
    createHandshakeSpan("servlet.request", url), // simulate server span
    url)

    runUnderTrace("parent") {
      session.close(new CloseReason(CloseReason.CloseCodes.getCloseCode(code), reason))
    }
    then:
    assertTraces(4, {
      DDSpan serverHandshake, clientHandshake
      trace(1) {
        basicSpan(it, "http.request", "GET /test", null, null, handshakeTags(url))
        clientHandshake = span(0)
      }
      trace(1) {
        basicSpan(it, "servlet.request", "GET /test", null, null, handshakeTags(url))
        serverHandshake = span(0)
      }
      trace(2) {
        sortSpansByStart()
        basicSpan(it, "parent")
        websocketCloseSpan(it, clientHandshake, true, code, reason, span(0))
      }

      trace(1) {
        websocketCloseSpan(it, serverHandshake, false, code, reason)
      }
    })
    where:
    code | reason
    1000 | null
    1000 | "bye"
    1001 | "see you"
  }

  def "test session id logged as tag"() {
    setup:
    injectSysConfig(TRACE_WEBSOCKET_TAG_SESSION_ID, "true")
    when:
    String url = "ws://inmemory/test"
    def session = deployEndpointAndConnect(new Endpoints.TestEndpoint(new Endpoints.FullStringHandler()),
    createHandshakeSpan("http.request", url),  //simulate client span
    createHandshakeSpan("servlet.request", url), // simulate server span
    url)

    runUnderTrace("parent") {
      session.getBasicRemote().sendText("Hello")
      session.close()
    }
    then:
    assertTraces(5, {
      DDSpan serverHandshake, clientHandshake
      trace(1) {
        basicSpan(it, "http.request", "GET /test", null, null, handshakeTags(url))
        clientHandshake = span(0)
      }
      trace(1) {
        basicSpan(it, "servlet.request", "GET /test", null, null, handshakeTags(url))
        serverHandshake = span(0)
      }
      trace(3) {
        sortSpansByStart()
        basicSpan(it, "parent")
        websocketSendSpan(it, clientHandshake, "text", 5, 1, span(0))
        websocketCloseSpan(it, clientHandshake, true, 1000, null, span(0))
      }
      trace(1) {
        websocketReceiveSpan(it, serverHandshake, "text", 5, 1)
      }
      trace(1) {
        websocketCloseSpan(it, serverHandshake, false, 1000, { it == null || it == 'no reason given' })
      }
    })
    //it's normally already tested by websocket(Receive|Send|Close)Span but we enforce that check
    TEST_WRITER.flatten().findAll { span -> (span as DDSpan).getSpanType() == "websocket" }.each {
      assert (it as DDSpan).getTag(InstrumentationTags.WEBSOCKET_SESSION_ID) != null
    }
  }

  def "test close and receive on same handshake trace"() {
    setup:
    injectSysConfig(TRACE_WEBSOCKET_MESSAGES_SEPARATE_TRACES, "false")
    when:
    String url = "ws://inmemory/test"
    def session = deployEndpointAndConnect(new Endpoints.TestEndpoint(new Endpoints.FullStringHandler()),
    createHandshakeSpan("http.request", url),  //simulate client span
    createHandshakeSpan("servlet.request", url), // simulate server span
    url)

    runUnderTrace("parent") {
      session.getBasicRemote().sendText("Hello")
      session.close()
    }
    then:
    // in reality we have 3 traces but since the handshake finishes soon, the trace structure writer is collecting 5 chunks
    assertTraces(5, {
      DDSpan serverHandshake, clientHandshake
      trace(1) {
        basicSpan(it, "http.request", "GET /test", null, null, handshakeTags(url))
        clientHandshake = span(0)
      }

      trace(1) {
        basicSpan(it, "servlet.request", "GET /test", null, null, handshakeTags(url))
        serverHandshake = span(0)
      }
      trace(1) {
        websocketReceiveSpan(it, serverHandshake, "text", 5, 1)
      }
      trace(1) {
        websocketCloseSpan(it, serverHandshake, false, 1000, { it == null || it == 'no reason given' }, serverHandshake)
      }
      trace(3) {
        sortSpansByStart()
        basicSpan(it, "parent")
        websocketSendSpan(it, clientHandshake, "text", 5, 1, span(0))
        websocketCloseSpan(it, clientHandshake, true, 1000, null, span(0))
      }
    })
  }

  def "test sampling not inherited"() {
    setup:
    injectSysConfig(TRACE_WEBSOCKET_MESSAGES_INHERIT_SAMPLING, "false")
    when:
    String url = "ws://inmemory/test"
    def session = deployEndpointAndConnect(new Endpoints.TestEndpoint(new Endpoints.FullStringHandler()),
    createHandshakeSpan("http.request", url),  //simulate client span
    createHandshakeSpan("servlet.request", url), // simulate server span
    url)

    runUnderTrace("parent") {
      session.getBasicRemote().sendText("Hello")
      session.close()
    }
    then:
    assertTraces(5, {
      DDSpan serverHandshake, clientHandshake
      trace(1) {
        basicSpan(it, "http.request", "GET /test", null, null, handshakeTags(url))
        clientHandshake = span(0)
      }
      trace(1) {
        basicSpan(it, "servlet.request", "GET /test", null, null, handshakeTags(url))
        serverHandshake = span(0)
      }
      trace(3) {
        sortSpansByStart()
        basicSpan(it, "parent")
        websocketSendSpan(it, clientHandshake, "text", 5, 1, span(0))
        websocketCloseSpan(it, clientHandshake, true, 1000, null, span(0))
      }
      trace(1) {
        websocketReceiveSpan(it, serverHandshake, "text", 5, 1)
      }
      trace(1) {
        websocketCloseSpan(it, serverHandshake, false, 1000, { it == null || it == 'no reason given' })
      }
    })
    //it's normally already tested by websocket(Receive|Send|Close)Span but we enforce that check
    TEST_WRITER.flatten().findAll { span -> (span as DDSpan).getSpanType() == "websocket" }.each {
      assert (it as DDSpan).getTag(DDTags.DECISION_MAKER_INHERITED) == null
    }
  }

  def "if handshake is not captured traces are not generated"() {
    when:
    String url = "ws://inmemory/test"
    def session = deployEndpointAndConnect(new Endpoints.TestEndpoint(new Endpoints.FullStringHandler()),
    null, null, url)

    runUnderTrace("parent") {
      session.getBasicRemote().sendText("Hello")
      session.close()
    }
    then:
    assertTraces(1, {
      trace(1) {
        basicSpan(it, "parent")
      }
    })
  }

  def "test send and receive object"() {
    when:
    String url = "ws://inmemory/test"
    def session = deployEndpointAndConnect(new Endpoints.TestEndpoint(new Endpoints.FullObjectHandler()),
    createHandshakeSpan("http.request", url),  //simulate client span
    createHandshakeSpan("servlet.request", url), // simulate server span
    url)

    runUnderTrace("parent") {
      session.getBasicRemote().sendObject(new Endpoints.CustomMessage())
      session.close()
    }
    then:
    assertTraces(5, {
      DDSpan serverHandshake, clientHandshake
      trace(1) {
        basicSpan(it, "http.request", "GET /test", null, null, handshakeTags(url))
        clientHandshake = span(0)
      }
      trace(1) {
        basicSpan(it, "servlet.request", "GET /test", null, null, handshakeTags(url))
        serverHandshake = span(0)
      }
      trace(3) {
        sortSpansByStart()
        basicSpan(it, "parent")
        websocketSendSpan(it, clientHandshake, null, 0, 1, span(0))
        websocketCloseSpan(it, clientHandshake, true, 1000, null, span(0))
      }
      trace(1) {
        websocketReceiveSpan(it, serverHandshake, null, 0, 1)
      }
      trace(1) {
        websocketCloseSpan(it, serverHandshake, false, 1000, { it == null || it == 'no reason given' })
      }
    })
  }
}
