package datadog.trace.core;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

import datadog.trace.api.KnownTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities;
import datadog.trace.bootstrap.instrumentation.api.SpanPrototype;
import datadog.trace.bootstrap.instrumentation.api.TagExtractor;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.TagsWriter;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * A rough model of one web-server span's whole lifecycle -- start, request tags, peer tags, route,
 * response status, finish -- as a web integration writes it today and as it could with the newer
 * pieces, to size the difference. The tag set follows {@code HttpServerDecorator}: the prototype's
 * constants, then {@code onRequest} (user agent, method, url, hostname, resource name), {@code
 * onPeerConnection} (peer ip, peer port), the framework's route, and {@code onResponse} (status).
 *
 * <ul>
 *   <li>{@code today}: start, apply the decorator's prototype in {@code afterStart}, then each tag
 *       by name -- except the peer ip, already by id -- with resource name and status set directly,
 *       one lock acquisition per tag.
 *   <li>{@code byId}: the same writes by constant id, still one acquisition per tag.
 *   <li>{@code future}: the prototype applied at start ({@code startSpan(prototype)}), and the
 *       request and response written by {@link TagExtractor}s through {@code setTagsFrom}: by id,
 *       one acquisition per extractor.
 * </ul>
 *
 * <p>A model, not a measurement of a real integration: no header tags, IG callbacks, query string
 * or client-ip inference, and strings stand in for the lazy URL. Spans finish against {@link
 * DropWriter}, so serialization is out of the picture.
 */
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(NANOSECONDS)
@Fork(value = 3)
public class WebServerShapeBenchmark {
  private static final String INSTRUMENTATION_NAME = "tomcat";
  private static final String OPERATION_NAME = "servlet.request";

  /** The request as a server integration sees it: what it reads per span. */
  static final class Request {
    final String method = "GET";
    final String url = "http://localhost:8080/owners/42";
    final String host = "localhost";
    final String userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)";
    final String peerIp = "10.0.0.7";
    final int peerPort = 54321;
    final String route = "/owners/{ownerId}";
    final String resourceName = "GET /owners/{ownerId}";
  }

  static final class Response {
    final int status = 200;
  }

  /** Writes the request's tags by id: what {@code onRequest} and {@code onPeerConnection} set. */
  static final class RequestExtractor implements TagExtractor<Request> {
    static final RequestExtractor INSTANCE = new RequestExtractor();

    @Override
    public void extract(Request request, TagsWriter writer) {
      writer.setTag(KnownTags.HTTP_USERAGENT_ID, request.userAgent);
      writer.setTag(KnownTags.HTTP_METHOD_ID, request.method);
      writer.setTag(KnownTags.HTTP_URL_ID, request.url);
      writer.setTag(KnownTags.HTTP_HOSTNAME_ID, request.host);
      writer.setTag(KnownTags.RESOURCE_NAME_ID, request.resourceName);
      writer.setTag(KnownTags.PEER_IPV4_ID, request.peerIp);
      writer.setTag(KnownTags.PEER_PORT_ID, request.peerPort);
      writer.setTag(KnownTags.HTTP_ROUTE_ID, request.route);
    }
  }

  static final class ResponseExtractor implements TagExtractor<Response> {
    static final ResponseExtractor INSTANCE = new ResponseExtractor();

    @Override
    public void extract(Response response, TagsWriter writer) {
      writer.setTag(KnownTags.HTTP_STATUS_CODE_ID, response.status);
    }
  }

  CoreTracer tracer;
  SpanPrototype prototype;
  Request request;
  Response response;

  @Setup
  public void setup(Blackhole blackhole) {
    tracer = CoreTracer.builder().writer(new DropWriter(blackhole)).build();
    prototype =
        SpanPrototype.builder()
            .initInstrumentationName(INSTRUMENTATION_NAME)
            .initOperationName(OPERATION_NAME)
            .initComponentAndIntegration("tomcat-server")
            .initKind(Tags.SPAN_KIND_SERVER)
            .initSpanType("web")
            .build();
    request = new Request();
    response = new Response();
  }

  @TearDown
  public void tearDown() {
    tracer.close();
  }

  /** Start and finish with no tags: subtract it to read each arm's tagging cost. */
  @Benchmark
  public void bare() {
    tracer.startSpan(INSTRUMENTATION_NAME, OPERATION_NAME).finish();
  }

  /** {@link #bare} with the prototype applied at start, as {@link #future} starts. */
  @Benchmark
  public void barePrototype() {
    tracer.startSpan(prototype, null).finish();
  }

  @Benchmark
  public void today() {
    Request request = this.request;
    AgentSpan span = tracer.startSpan(INSTRUMENTATION_NAME, OPERATION_NAME).setMeasured(true);
    span.applyOverwriting(prototype);
    span.setTag(Tags.HTTP_USER_AGENT, request.userAgent);
    span.setTag(Tags.HTTP_METHOD, request.method);
    span.setTag(Tags.HTTP_URL, request.url);
    span.setTag(Tags.HTTP_HOSTNAME, request.host);
    span.setResourceName(request.resourceName, ResourceNamePriorities.HTTP_PATH_NORMALIZER);
    span.setTag(KnownTags.PEER_IPV4_ID, request.peerIp);
    span.setTag(Tags.PEER_PORT, request.peerPort);
    span.setTag(Tags.HTTP_ROUTE, request.route);
    span.setHttpStatusCode(response.status);
    span.finish();
  }

  @Benchmark
  public void byId() {
    Request request = this.request;
    AgentSpan span = tracer.startSpan(INSTRUMENTATION_NAME, OPERATION_NAME).setMeasured(true);
    span.applyOverwriting(prototype);
    span.setTag(KnownTags.HTTP_USERAGENT_ID, request.userAgent);
    span.setTag(KnownTags.HTTP_METHOD_ID, request.method);
    span.setTag(KnownTags.HTTP_URL_ID, request.url);
    span.setTag(KnownTags.HTTP_HOSTNAME_ID, request.host);
    span.setResourceName(request.resourceName, ResourceNamePriorities.HTTP_PATH_NORMALIZER);
    span.setTag(KnownTags.PEER_IPV4_ID, request.peerIp);
    span.setTag(KnownTags.PEER_PORT_ID, request.peerPort);
    span.setTag(KnownTags.HTTP_ROUTE_ID, request.route);
    span.setHttpStatusCode(response.status);
    span.finish();
  }

  @Benchmark
  public void future() {
    AgentSpan span = tracer.startSpan(prototype, null).setMeasured(true);
    span.setTagsFrom(request, RequestExtractor.INSTANCE);
    span.setTagsFrom(response, ResponseExtractor.INSTANCE);
    span.finish();
  }
}
