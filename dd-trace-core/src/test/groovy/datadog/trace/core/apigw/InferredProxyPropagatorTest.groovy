package datadog.trace.core.apigw

import datadog.context.Context
import datadog.trace.bootstrap.instrumentation.api.ContextVisitors
import datadog.trace.bootstrap.instrumentation.api.InferredProxyContext
import datadog.trace.core.test.DDCoreSpecification

class InferredProxyPropagatorTest extends DDCoreSpecification {

  def propagator = new InferredProxyPropagator()

  def "extract inferred proxy http headers"() {
    setup:
    def headers = [
      (InferredProxyPropagator.INFERRED_PROXY_KEY): "aws-apigateway",
      (InferredProxyPropagator.REQUEST_TIME_KEY): "1672531200000",
      (InferredProxyPropagator.DOMAIN_NAME_KEY): "example.com",
      (InferredProxyPropagator.HTTP_METHOD_KEY): "GET",
      (InferredProxyPropagator.PATH_KEY): "/test/path",
      (InferredProxyPropagator.STAGE_KEY): "prod"
    ]

    when:
    final Context context = propagator.extract(Context.root(), headers, ContextVisitors.stringValuesMap())
    final InferredProxyContext inferredProxyContext = context.get(InferredProxyContext.CONTEXT_KEY)

    then:
    inferredProxyContext != null
    inferredProxyContext.getProxyName() == "aws.apigateway"
    Long.parseLong(inferredProxyContext.getStartTime()) == 1672531200000L
    inferredProxyContext.getDomainName() == "example.com"
    inferredProxyContext.getHttpMethod() == "GET"
    inferredProxyContext.getPath() == "/test/path"
    inferredProxyContext.getStage() == "prod"
  }

  def "extract with missing mandatory headers should not create context"() {
    setup:
    def headers = [
      // Missing InferredProxyPropagator.INFERRED_PROXY_KEY
      (InferredProxyPropagator.REQUEST_TIME_KEY): "1672531200000",
      (InferredProxyPropagator.DOMAIN_NAME_KEY): "example.com",
      (InferredProxyPropagator.HTTP_METHOD_KEY): "GET",
      (InferredProxyPropagator.PATH_KEY): "/test/path",
      (InferredProxyPropagator.STAGE_KEY): "prod"
    ]

    when:
    final Context context = propagator.extract(Context.root(), headers, ContextVisitors.stringValuesMap())
    final InferredProxyContext inferredProxyContext = context.get(InferredProxyContext.CONTEXT_KEY)

    then:
    inferredProxyContext == null
  }

  def "extract with unsupported proxy should not create context"() {
    setup:
    def headers = [
      (InferredProxyPropagator.INFERRED_PROXY_KEY): "unsupported-proxy",
      (InferredProxyPropagator.REQUEST_TIME_KEY): "1672531200000",
      (InferredProxyPropagator.DOMAIN_NAME_KEY): "example.com",
      (InferredProxyPropagator.HTTP_METHOD_KEY): "GET",
      (InferredProxyPropagator.PATH_KEY): "/test/path",
      (InferredProxyPropagator.STAGE_KEY): "prod"
    ]

    when:
    final Context context = propagator.extract(Context.root(), headers, ContextVisitors.stringValuesMap())
    final InferredProxyContext inferredProxyContext = context.get(InferredProxyContext.CONTEXT_KEY)

    then:
    inferredProxyContext == null
  }

  def "extract with only proxy key should not create context"() {
    setup:
    def headers = [
      (InferredProxyPropagator.INFERRED_PROXY_KEY): "aws-apigateway"
    ]

    when:
    final Context context = propagator.extract(Context.root(), headers, ContextVisitors.stringValuesMap())
    final InferredProxyContext inferredProxyContext = context.get(InferredProxyContext.CONTEXT_KEY)

    then:
    inferredProxyContext == null
  }
}
