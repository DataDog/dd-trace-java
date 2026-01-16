package datadog.trace.instrumentation.akkahttp.appsec;

import akka.http.scaladsl.model.HttpRequest;
import akka.http.scaladsl.model.HttpResponse;
import datadog.appsec.api.blocking.BlockingContentType;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.Map;

/**
 * This block response function only saves the request blocking action. Usually the blocking request
 * function directly commits a response.
 *
 * @see BlockingResponseHelper#handleFinishForWaf(AgentSpan, HttpResponse)
 */
public class AkkaBlockResponseFunction implements BlockResponseFunction {
  private final HttpRequest request;
  private Flow.Action.RequestBlockingAction rba;
  private boolean unmarshallBlock;
  private TraceSegment traceSegment;

  public AkkaBlockResponseFunction(HttpRequest request) {
    this.request = request;
  }

  public boolean isBlocking() {
    return rba != null;
  }

  public boolean isUnmarshallBlock() {
    return unmarshallBlock;
  }

  public void setUnmarshallBlock(boolean unmarshallBlock) {
    this.unmarshallBlock = unmarshallBlock;
  }

  public HttpResponse maybeCreateAlternativeResponse() {
    if (!isBlocking()) {
      return null;
    }

    HttpResponse httpResponse = BlockingResponseHelper.maybeCreateBlockingResponse(rba, request);
    if (httpResponse != null) {
      traceSegment.effectivelyBlocked();
    }
    return httpResponse;
  }

  @Override
  public boolean tryCommitBlockingResponse(
      TraceSegment segment,
      int statusCode,
      BlockingContentType templateType,
      Map<String, String> extraHeaders,
      String securityResponseId) {
    AgentSpan agentSpan = AgentTracer.activeSpan();
    if (agentSpan == null) {
      return false;
    }
    if (rba == null) {
      rba =
          new Flow.Action.RequestBlockingAction(
              statusCode, templateType, extraHeaders, securityResponseId);
      this.traceSegment = segment;
    }
    return true;
  }
}
