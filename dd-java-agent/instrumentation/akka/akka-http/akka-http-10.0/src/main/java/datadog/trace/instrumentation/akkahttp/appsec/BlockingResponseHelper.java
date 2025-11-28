package datadog.trace.instrumentation.akkahttp.appsec;

import static datadog.trace.instrumentation.akkahttp.AkkaHttpServerDecorator.DECORATE;

import akka.http.javadsl.model.HttpHeader;
import akka.http.javadsl.model.headers.RawHeader;
import akka.http.scaladsl.model.ContentTypes;
import akka.http.scaladsl.model.HttpEntity$;
import akka.http.scaladsl.model.HttpRequest;
import akka.http.scaladsl.model.HttpResponse;
import akka.http.scaladsl.model.ResponseEntity;
import akka.http.scaladsl.model.StatusCode;
import akka.http.scaladsl.model.StatusCodes;
import akka.util.ByteString;
import datadog.appsec.api.blocking.BlockingContentType;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.bootstrap.blocking.BlockingActionHelper;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.instrumentation.akkahttp.AkkaHttpServerHeaders;
import java.util.Optional;
import scala.collection.immutable.List;

public class BlockingResponseHelper {
  private BlockingResponseHelper() {}

  public static HttpResponse handleFinishForWaf(final AgentSpan span, final HttpResponse response) {
    RequestContext requestContext = span.getRequestContext();
    BlockResponseFunction brf = requestContext.getBlockResponseFunction();
    if (brf instanceof AkkaBlockResponseFunction) {
      HttpResponse altResponse = ((AkkaBlockResponseFunction) brf).maybeCreateAlternativeResponse();
      if (altResponse != null) {
        // we already blocked during the request
        return altResponse;
      }
    }
    Flow<Void> flow =
        DECORATE.callIGCallbackResponseAndHeaders(
            span, response, response.status().intValue(), AkkaHttpServerHeaders.responseGetter());
    Flow.Action action = flow.getAction();
    if (action instanceof Flow.Action.RequestBlockingAction) {
      Flow.Action.RequestBlockingAction rba = (Flow.Action.RequestBlockingAction) action;
      if (brf instanceof AkkaBlockResponseFunction) {
        brf.tryCommitBlockingResponse(
            requestContext.getTraceSegment(),
            rba.getStatusCode(),
            rba.getBlockingContentType(),
            rba.getExtraHeaders(),
            rba.getSecurityResponseId());
        HttpResponse altResponse =
            ((AkkaBlockResponseFunction) brf).maybeCreateAlternativeResponse();
        if (altResponse != null) {
          return altResponse;
        }
      }
    }

    return response;
  }

  public static HttpResponse maybeCreateBlockingResponse(AgentSpan span, HttpRequest request) {
    return maybeCreateBlockingResponse(span.getRequestBlockingAction(), request);
  }

  public static HttpResponse maybeCreateBlockingResponse(
      Flow.Action.RequestBlockingAction rba, HttpRequest request) {
    if (rba == null) {
      return null;
    }
    Optional<HttpHeader> accept = request.getHeader("accept");
    BlockingContentType bct = rba.getBlockingContentType();
    int httpCode = BlockingActionHelper.getHttpCode(rba.getStatusCode());
    ResponseEntity entity;
    if (bct != BlockingContentType.NONE) {
      BlockingActionHelper.TemplateType tt =
          BlockingActionHelper.determineTemplateType(bct, accept.map(h -> h.value()).orElse(null));
      byte[] template = BlockingActionHelper.getTemplate(tt, rba.getSecurityResponseId());
      if (tt == BlockingActionHelper.TemplateType.HTML) {
        entity =
            HttpEntity$.MODULE$.apply(
                ContentTypes.text$divhtml$u0028UTF$minus8$u0029(), ByteString.fromArray(template));
      } else { // json
        entity =
            HttpEntity$.MODULE$.apply(
                ContentTypes.application$divjson(), ByteString.fromArray(template));
      }
    } else {
      entity = HttpEntity$.MODULE$.Empty();
    }

    List<akka.http.scaladsl.model.HttpHeader> headersList =
        rba.getExtraHeaders().entrySet().stream()
            .map(
                e ->
                    (akka.http.scaladsl.model.HttpHeader)
                        RawHeader.create(e.getKey(), e.getValue()))
            .collect(ScalaListCollector.toScalaList());

    StatusCode code;
    try {
      code = StatusCode.int2StatusCode(httpCode);
    } catch (RuntimeException e) {
      code = StatusCodes.custom(httpCode, "Request Blocked", "", false, true);
    }
    return HttpResponse.apply(code, headersList, entity, request.protocol());
  }
}
