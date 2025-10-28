package datadog.trace.instrumentation.resteasy

import datadog.appsec.api.blocking.BlockingContentType
import datadog.appsec.api.blocking.BlockingException
import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.agent.test.utils.OkHttpUtils
import datadog.trace.api.function.TriConsumer
import datadog.trace.api.gateway.Events
import datadog.trace.api.gateway.Flow
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import okhttp3.HttpUrl
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.codehaus.jackson.map.ObjectMapper
import org.jboss.resteasy.plugins.providers.multipart.MultipartFormDataInput
import spock.lang.Shared

import javax.ws.rs.Consumes
import javax.ws.rs.FormParam
import javax.ws.rs.GET
import javax.ws.rs.POST
import javax.ws.rs.Path
import javax.ws.rs.PathParam
import javax.ws.rs.Produces
import javax.ws.rs.core.Application
import javax.ws.rs.core.MediaType
import javax.ws.rs.core.PathSegment
import javax.ws.rs.core.Response
import javax.ws.rs.ext.ContextResolver
import java.util.concurrent.TimeUnit
import java.util.function.BiFunction
import java.util.function.Supplier

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.BODY_JSON
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.BODY_URLENCODED

abstract class AbstractResteasyAppsecTest extends InstrumentationSpecification {

  @Shared
  URI address

  @Shared
  OkHttpClient client = OkHttpUtils.client(15, 15, TimeUnit.SECONDS)

  @Shared
  def ig

  abstract void startServer()
  abstract void stopServer()

  static class TestCtx {
    boolean block

    Flow<Void> getFlow() {
      if (block) {
        BlockingFlow.INSTANCE
      } else {
        Flow.ResultFlow.empty()
      }
    }
  }

  enum BlockingFlow implements Flow<Void> {
    INSTANCE

    @Override
    Action getAction() {
      new Action.RequestBlockingAction(403, BlockingContentType.JSON)
    }

    final Void result = null
  }

  def setupSpec() {
    Events<Object> events = Events.get()
    ig = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC)
    ig.registerCallback(events.requestHeader(), { RequestContext ctx, String name, String value ->
      if (name == 'x-block') {
        TestCtx testCtx = ctx.getData(RequestContextSlot.APPSEC)
        testCtx.block = true
      }
    } as TriConsumer<RequestContext, String, String>)
    ig.registerCallback(events.requestStarted(), { -> new Flow.ResultFlow<Object>(new TestCtx()) } as Supplier<Flow<Object>>)
    ig.registerCallback(events.requestBodyProcessed(), { RequestContext ctx, Object obj ->
      ctx.traceSegment.setTagTop('request.body.converted', obj as String)
      TestCtx testCtx = ctx.getData(RequestContextSlot.APPSEC)
      testCtx.flow
    } as BiFunction<RequestContext, Object, Flow<Void>>)
    ig.registerCallback(events.requestPathParams(), { RequestContext ctx, Map<String, ?> stringMap ->
      ctx.traceSegment.setTagTop('request.path_params', stringMap as String)
      TestCtx testCtx = ctx.getData(RequestContextSlot.APPSEC)
      testCtx.flow
    } as BiFunction<RequestContext, Map<String, ?>, Flow<Void>>)

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

  def 'test instrumentation gateway urlencoded request body — blocking'() {
    setup:
    def request = request(
      BODY_URLENCODED, 'POST',
      RequestBody.create(okhttp3.MediaType.get('application/x-www-form-urlencoded'), 'a=x'))
      .header('x-block', 'true')
      .build()
    def response = client.newCall(request).execute()

    expect:
    response.code() == 403
    response.body().charStream().text.contains('"title":"You\'ve been blocked"')

    when:
    TEST_WRITER.waitForTraces(1)

    then:
    TEST_WRITER.get(0).any {
      it.error &&
        it.tags['error.type'] == BlockingException.name
    } != null
  }

  def 'test instrumentation gateway json request body'() {
    setup:
    def request = request(
      BODY_JSON, 'POST',
      RequestBody.create(okhttp3.MediaType.get('application/json'), '{"a":"x"}\n'))
      .build()
    def response = client.newCall(request).execute()

    expect:
    response.body().charStream().text == '{"a":"x"}'

    when:
    TEST_WRITER.waitForTraces(1)

    then:
    TEST_WRITER.get(0).any {
      it.getTag('request.body.converted') == '[a:[x]]'
    }
  }

  def 'test instrumentation gateway json request body — blocking'() {
    setup:
    def request = request(
      BODY_JSON, 'POST',
      RequestBody.create(okhttp3.MediaType.get('application/json'), '{"a":"x"}\n'))
      .header('x-block', 'true')
      .build()
    def response = client.newCall(request).execute()

    expect:
    response.code() == 403
    response.body().charStream().text.contains('"title":"You\'ve been blocked"')

    when:
    TEST_WRITER.waitForTraces(1)

    then:
    TEST_WRITER.get(0).any {
      it.error &&
        it.tags['error.type'] == BlockingException.name
    } != null
  }

  def 'test instrumentation gateway path params for #uri'() {
    setup:
    def url = HttpUrl.get(address.resolve(uri)).newBuilder().build()
    def request = new Request.Builder().url(url).method('GET', null).build()
    def response = client.newCall(request).execute()

    expect:
    response.body().charStream().text == 'foobar'

    when:
    TEST_WRITER.waitForTraces(1)

    then:
    TEST_WRITER.get(0).any {
      it.getTag('request.path_params') == '[p:[foobar]]'
    }

    where:
    uri << ["/paramString/foobar", "/paramSegment/foobar",]
  }

  def 'test instrumentation gateway path params — blocking'() {
    setup:
    def url = HttpUrl.get(address.resolve("/paramString/foobar"))
    def request = new Request.Builder().url(url)
      .header('x-block', 'true')
      .method('GET', null).build()
    def response = client.newCall(request).execute()

    expect:
    response.code() == 403
    response.body().charStream().text.contains('"title":"You\'ve been blocked"')

    when:
    TEST_WRITER.waitForTraces(1)

    then:
    TEST_WRITER.get(0).any {
      it.error &&
        it.tags['error.type'] == BlockingException.name
    } != null
  }

  def 'test instrumentation gateway multipart request body endpoint #endpoint'() {
    setup:
    def url = HttpUrl.get(address.resolve(endpoint))
    RequestBody formBody = new MultipartBody.Builder()
      .setType(MultipartBody.FORM)
      .addFormDataPart('a', 'x').build()
    def request = new Request.Builder().url(url).method('POST', formBody).build()
    def response = client.newCall(request).execute()

    expect:
    response.body().charStream().text in ['[a:[x]]', '[a:x]']

    when:
    TEST_WRITER.waitForTraces(1)

    then:
    TEST_WRITER.get(0).any {
      it.getTag('request.body.converted') in ['[a:[x]]', '[a:x]']
    }

    where:
    endpoint << ['/body-multipart', '/body-multipart-map']
  }

  def 'test instrumentation gateway multipart request body — blocking'() {
    setup:
    def url = HttpUrl.get(address.resolve('/body-multipart'))
    RequestBody formBody = new MultipartBody.Builder()
      .setType(MultipartBody.FORM)
      .addFormDataPart('a', 'x').build()
    def request = new Request.Builder().url(url)
      .header('x-block', 'true')
      .method('POST', formBody).build()
    def response = client.newCall(request).execute()

    expect:
    response.code() == 403
    response.body().charStream().text.contains('"title":"You\'ve been blocked"')

    when:
    TEST_WRITER.waitForTraces(1)

    then:
    TEST_WRITER.get(0).any {
      it.error &&
        it.tags['error.type'] == BlockingException.name
    } != null
  }

  @Path("/")
  static class BodyResource {
    @POST
    @Path("body-urlencoded")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    Response bodyUrlencoded(@FormParam("a") List<String> a) {
      Response.status(BODY_URLENCODED.status).entity([a: a] as String).build()
    }

    @POST
    @Path("body-multipart")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    Response bodyMultipart(MultipartFormDataInput input) {
      def map = input.formDataMap.collectEntries {
        [it.key, it.value.collect { p -> p.bodyAsString }]
      }
      Response.status(200).entity(map as String).build()
    }

    @POST
    @Path("body-multipart-map")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    Response bodyMultipartMap(Map<String, String> map) {
      Response.status(200).entity(map as String).build()
    }

    @POST
    @Path("body-json")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    Response bodyJson(ClassForBodyToBeConvertedTo obj) {
      Response.status(BODY_JSON.status).entity(obj).build()
    }
  }

  @Path("/paramString")
  static class ParamResource {
    @GET
    @Path("{p}")
    Response paramString(@PathParam("p") String p) {
      Response.status(200).entity(p).build()
    }
  }

  @Path("/paramSegment")
  static class ParamSegmentResource {
    @GET
    @Path("{p}")
    Response paramString(@PathParam("p") PathSegment seg) {
      Response.status(200).entity(seg.path).build()
    }
  }

  static class ClassForBodyToBeConvertedTo {
    String a

    @Override
    String toString() {
      "[a:[$a]]"
    }
  }

  /* avoid the jackson provider looking for jaxb annotations */
  static class ObjectMapperContextResolver implements ContextResolver<ObjectMapper> {
    @Override
    ObjectMapper getContext(Class<?> type) {
      new ObjectMapper()
    }
  }

  static class TestJaxRsApplication extends Application {
    private static final RESOURCE = new BodyResource()
    private static final RESOURCE_PARAM_STRING = new ParamResource()
    private static final RESOURCE_PARAM_SEGMENT = new ParamSegmentResource()
    private static final OBJECT_MAPPER_CONTEXT_RESOLVER = new ObjectMapperContextResolver()
    final Set<Object> singletons = [RESOURCE, RESOURCE_PARAM_STRING, RESOURCE_PARAM_SEGMENT, OBJECT_MAPPER_CONTEXT_RESOLVER] as Set
  }
}
