package datadog.trace.instrumentation.grizzly;

import datadog.appsec.api.blocking.BlockingContentType;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator;
import java.util.Map;
import org.glassfish.grizzly.http.server.Request;
import org.glassfish.grizzly.http.server.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GrizzlyDecorator extends HttpServerDecorator<Request, Request, Response, Request> {
  public static final CharSequence GRIZZLY = UTF8BytesString.create("grizzly");
  public static final CharSequence GRIZZLY_REQUEST = UTF8BytesString.create("grizzly.request");
  public static final GrizzlyDecorator DECORATE = new GrizzlyDecorator();

  @Override
  protected AgentPropagation.ContextVisitor<Request> getter() {
    return ExtractAdapter.Request.GETTER;
  }

  @Override
  protected AgentPropagation.ContextVisitor<Response> responseGetter() {
    return ExtractAdapter.Response.GETTER;
  }

  @Override
  public CharSequence spanName() {
    return GRIZZLY_REQUEST;
  }

  @Override
  protected String method(final Request request) {
    return request.getMethod().getMethodString();
  }

  @Override
  protected URIDataAdapter url(final Request request) {
    return new RequestURIDataAdapter(request);
  }

  @Override
  protected String peerHostIP(final Request request) {
    return request.getRemoteAddr();
  }

  @Override
  protected int peerPort(final Request request) {
    return request.getRemotePort();
  }

  @Override
  protected int status(final Response containerResponse) {
    return containerResponse.getStatus();
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"grizzly"};
  }

  @Override
  protected CharSequence component() {
    return GRIZZLY;
  }

  @Override
  protected BlockResponseFunction createBlockResponseFunction(Request request, Request request2) {
    return new GrizzlyBlockResponseFunction(request);
  }

  public static class GrizzlyBlockResponseFunction implements BlockResponseFunction {
    private static final Logger log = LoggerFactory.getLogger(GrizzlyBlockResponseFunction.class);

    private final Request request;

    public GrizzlyBlockResponseFunction(Request request) {
      this.request = request;
    }

    @Override
    public boolean tryCommitBlockingResponse(
        TraceSegment segment,
        int statusCode,
        BlockingContentType templateType,
        Map<String, String> extraHeaders,
        String securityResponseId) {
      AgentSpan agentSpan = AgentTracer.get().activeSpan();
      if (agentSpan == null) {
        log.warn("Can't block: no active span");
        return false;
      }

      return GrizzlyBlockingHelper.block(
          this.request,
          this.request.getResponse(),
          statusCode,
          templateType,
          extraHeaders,
          agentSpan);
    }
  }
}
