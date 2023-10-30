package datadog.trace.instrumentation.akkahttp;

import akka.http.javadsl.model.HttpHeader;
import akka.http.javadsl.model.headers.RawRequestURI;
import akka.http.javadsl.model.headers.RemoteAddress;
import akka.http.javadsl.model.headers.TimeoutAccess;
import akka.http.scaladsl.model.ContentType;
import akka.http.scaladsl.model.HttpEntity;
import akka.http.scaladsl.model.HttpMessage;
import akka.http.scaladsl.model.HttpRequest;
import akka.http.scaladsl.model.HttpResponse;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;

public class AkkaHttpServerHeaders {
  private AkkaHttpServerHeaders() {}

  private static final AgentPropagation.ContextVisitor<HttpRequest> GETTER_REQUEST =
      AkkaHttpServerHeaders::forEachKeyRequest;
  private static final AgentPropagation.ContextVisitor<HttpResponse> GETTER_RESPONSE =
      AkkaHttpServerHeaders::forEachKeyResponse;

  public static AgentPropagation.ContextVisitor<HttpRequest> requestGetter() {
    return GETTER_REQUEST;
  }

  public static AgentPropagation.ContextVisitor<HttpResponse> responseGetter() {
    return GETTER_RESPONSE;
  }

  private static void doForEachKey(
      HttpMessage carrier,
      akka.http.javadsl.model.HttpEntity entity,
      AgentPropagation.KeyClassifier classifier) {
    if (entity instanceof HttpEntity.Strict) {
      HttpEntity.Strict strictEntity = (HttpEntity.Strict) entity;
      ContentType contentType = strictEntity.contentType();
      if (contentType != null) {
        if (!classifier.accept("content-type", contentType.value())) {
          return;
        }
      }
      if (!classifier.accept("content-length", Long.toString(strictEntity.contentLength()))) {
        return;
      }
    }

    for (final HttpHeader header : carrier.getHeaders()) {
      // skip synthetic headers
      if (header instanceof RemoteAddress
          || header instanceof TimeoutAccess
          || header instanceof RawRequestURI) {
        continue;
      }
      if (!classifier.accept(header.lowercaseName(), header.value())) {
        return;
      }
    }
  }

  private static void forEachKeyRequest(
      HttpRequest req, AgentPropagation.KeyClassifier classifier) {
    doForEachKey(req, req.entity(), classifier);
  }

  private static void forEachKeyResponse(
      final HttpResponse resp, final AgentPropagation.KeyClassifier classifier) {
    doForEachKey(resp, resp.entity(), classifier);
  }
}
