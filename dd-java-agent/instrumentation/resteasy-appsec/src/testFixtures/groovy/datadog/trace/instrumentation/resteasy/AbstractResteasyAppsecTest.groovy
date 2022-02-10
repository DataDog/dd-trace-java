package datadog.trace.instrumentation.resteasy

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.agent.test.utils.OkHttpUtils
import datadog.trace.api.function.BiFunction
import datadog.trace.api.function.Supplier
import datadog.trace.api.gateway.Events
import datadog.trace.api.gateway.Flow
import datadog.trace.api.gateway.RequestContext
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import spock.lang.Shared

import javax.ws.rs.Consumes
import javax.ws.rs.FormParam
import javax.ws.rs.POST
import javax.ws.rs.Path
import javax.ws.rs.core.Application
import javax.ws.rs.core.MediaType
import javax.ws.rs.core.Response
import java.util.concurrent.TimeUnit

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.BODY_URLENCODED
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan

abstract class AbstractResteasyAppsecTest extends AgentTestRunner {

  @Shared
  URI address

  @Shared
  OkHttpClient client = OkHttpUtils.client(15, 15, TimeUnit.SECONDS)

  @Shared
  def ig

  abstract void startServer()
  abstract void stopServer()

  def setupSpec() {
    Events<Object> events = Events.get()
    ig = AgentTracer.get().instrumentationGateway()
    ig.registerCallback(events.requestStarted(), { -> new Flow.ResultFlow<Object>(new Object()) } as Supplier<Flow<Object>>)
    ig.registerCallback(events.requestBodyProcessed(), { RequestContext<Object> ctx, Object obj ->
      ctx.traceSegment.setTagTop('request.body.converted', obj as String)
      Flow.ResultFlow.empty()
    } as BiFunction<RequestContext<Object>, Object, Flow<Void>>)

    startServer()
  }

  def cleanupSpec() {
    stopServer()
    ig.reset()
  }

  Request.Builder request(HttpServerTest.ServerEndpoint uri, String method, RequestBody body) {
    def url = HttpUrl.get(uri.resolve(address)).newBuilder()
      .encodedQuery(uri.rawQuery)
      .fragment(uri.fragment)
      .build()
    new Request.Builder()
      .url(url)
      .method(method, body)
  }

  def 'test instrumentation gateway urlencoded request body'() {
    setup:
    def request = request(
      BODY_URLENCODED, 'POST',
      RequestBody.create(okhttp3.MediaType.get('application/x-www-form-urlencoded'), 'a=x'))
      .build()
    def response = client.newCall(request).execute()

    expect:
    response.body().charStream().text == '[a:[x]]'

    when:
    TEST_WRITER.waitForTraces(1)

    then:
    TEST_WRITER.get(0).any {
      it.getTag('request.body.converted') == '[a:[x]]'
    }
  }

  @Path("/body-urlencoded")
  static class BodyUrlEncodedResource {
    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    Response bodyUrlencoded(@FormParam("a") List<String> a) {
      Response.status(BODY_URLENCODED.status).entity([a: a] as String).build()
    }
  }

  static class TestJaxRsApplication extends Application {
    private static final RESOURCE = new AbstractResteasyAppsecTest.BodyUrlEncodedResource()
    final Set<Object> singletons = [RESOURCE] as Set
  }
}
