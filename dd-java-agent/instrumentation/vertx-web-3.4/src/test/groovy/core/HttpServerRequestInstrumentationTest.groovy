//file:noinspection GroovyAccessibility
//file:noinspection GroovyAssignabilityCheck
package core

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.SourceTypes
import datadog.trace.api.iast.propagation.PropagationModule
import groovy.transform.CompileDynamic
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http2.Http2Headers
import io.vertx.core.buffer.Buffer
import io.vertx.core.buffer.impl.BufferImpl
import io.vertx.core.http.impl.Http2ServerConnection
import io.vertx.core.http.impl.Http2ServerRequestImpl
import io.vertx.core.http.impl.HttpServerRequestImpl
import io.vertx.core.impl.ContextImpl
import io.vertx.core.spi.metrics.HttpServerMetrics

import static org.mockito.Mockito.mock
import static org.mockito.Mockito.when

@CompileDynamic
class HttpServerRequestInstrumentationTest extends AgentTestRunner {

  @Override
  protected void configurePreAgent() {
    injectSysConfig('dd.iast.enabled', 'true')
  }

  void 'test that headers() is instrumented'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    request.headers()

    then:
    1 * module.taint(SourceTypes.REQUEST_HEADER_VALUE, _)

    where:
    request                                                      | _
    mockHttServerRequest('/hello?name=value')                    | _
    mockHtt2ServerRequest(mockHttp2Headers('/hello?name=value')) | _
  }

  void 'test that params() is instrumented'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    request.params()

    then:
    1 * module.taint(SourceTypes.REQUEST_PARAMETER_VALUE, _)

    when:
    request.params()

    then:
    0 * _

    where:
    request                                                      | _
    mockHttServerRequest('/hello?name=value')                    | _
    mockHtt2ServerRequest(mockHttp2Headers('/hello?name=value')) | _
  }

  void 'test that formAttributes() is instrumented'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    request.formAttributes()

    then:
    1 * module.taint(SourceTypes.REQUEST_PARAMETER_VALUE, _)

    when:
    request.formAttributes()

    then:
    0 * _

    where:
    request                                           | _
    mockHttServerRequest('/hello')                    | _
    mockHtt2ServerRequest(mockHttp2Headers('/hello')) | _
  }

  void 'test that handleData()/onData() is instrumented'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    request.handler { }
    final buffer = new BufferImpl('hello')

    when:
    if (hasMethod(request.getClass(), 'onData', Buffer)) {
      request.onData(buffer)
    } else {
      request.handleData(buffer)
    }

    then:
    1 * module.taint(SourceTypes.REQUEST_BODY, buffer)

    where:
    request                                           | _
    mockHttServerRequest('/hello')                    | _
    mockHtt2ServerRequest(mockHttp2Headers('/hello')) | _
  }

  private HttpServerRequestImpl mockHttServerRequest(final String uri) {
    final ctx = mock(ContextImpl)
    final req = mock(HttpRequest)
    when(req.uri()).thenReturn(uri)
    final serverConn = loadClass('io.vertx.core.http.impl.ServerConnection')
    if (serverConn != null) {
      final conn = mock(serverConn)
      when(conn.getContext()).thenReturn(ctx)
      return new HttpServerRequestImpl(conn, req, null)
    }
    final conn = mock(loadClass('io.vertx.core.http.impl.Http1xServerConnection'))
    return new HttpServerRequestImpl(conn, req)
  }

  private Http2Headers mockHttp2Headers(final String uri) {
    final headers = mock(Http2Headers)
    when(headers.get(':path')).thenReturn(uri)
    when(headers.get(':method')).thenReturn('GET')
    when(headers.path()).thenReturn(uri)
    return headers
  }

  private Http2ServerRequestImpl mockHtt2ServerRequest(final Http2Headers headers) {
    final ctx = mock(ContextImpl)
    final conn = mock(Http2ServerConnection)
    when(conn.getContext()).thenReturn(ctx)
    final metrics = Mock(HttpServerMetrics) {
      isEnabled() >> false
    }
    final requestCtor = Http2ServerRequestImpl.declaredConstructors.first()
    if (requestCtor.parameterCount == 7) {
      return new Http2ServerRequestImpl(conn, null, metrics, 'http://localhost:80', headers, 'UTF-8', false)
    }
    return new Http2ServerRequestImpl(conn, null, metrics, 'http://localhost:80', headers, 'UTF-8', false, false)
  }

  private static boolean hasMethod(final Class<?> target, final String name, final Class<?>...types) {
    try {
      target.getDeclaredMethod(name, types) != null
    } catch (Throwable e) {
      return false
    }
  }

  private static Class<?> loadClass(final String name) {
    try {
      return Thread.currentThread().contextClassLoader.loadClass(name)
    } catch (Exception e) {
      return null
    }
  }
}
