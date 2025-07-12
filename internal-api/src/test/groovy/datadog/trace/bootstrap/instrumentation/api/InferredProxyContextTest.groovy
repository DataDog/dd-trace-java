package datadog.trace.bootstrap.instrumentation.api

import datadog.context.Context
import spock.lang.Specification

class InferredProxyContextTest extends Specification {

  def "test context validity"() {
    setup:
    def context = new InferredProxyContext()

    expect:
    !context.validContext()

    when:
    context.setProxyName("aws.apigateway")
    context.setStartTime("123")
    context.setDomainName("example.com")
    context.setHttpMethod("GET")
    context.setPath("/foo")

    then:
    !context.validContext()

    when:
    context.setStage("prod")

    then:
    context.validContext()
  }

  def "test fromContext and storeInto"() {
    setup:
    def inferredProxyContext = new InferredProxyContext()
    inferredProxyContext.setProxyName("aws.apigateway")
    inferredProxyContext.setStartTime("123")
    inferredProxyContext.setDomainName("example.com")
    inferredProxyContext.setHttpMethod("GET")
    inferredProxyContext.setPath("/foo")
    inferredProxyContext.setStage("prod")

    when:
    def context = inferredProxyContext.storeInto(Context.root())
    def extractedContext = InferredProxyContext.fromContext(context)

    then:
    extractedContext == inferredProxyContext
    extractedContext.getProxyName() == "aws.apigateway"
  }

  def "test fromContext with no inferred proxy context"() {
    when:
    def extractedContext = InferredProxyContext.fromContext(Context.root())

    then:
    extractedContext == null
  }

  def "test getters and setters"() {
    setup:
    def context = new InferredProxyContext()

    when:
    context.setProxyName("aws.apigateway")
    context.setStartTime("123")
    context.setDomainName("example.com")
    context.setHttpMethod("GET")
    context.setPath("/foo")
    context.setStage("prod")

    then:
    context.getProxyName() == "aws.apigateway"
    context.getStartTime() == "123"
    context.getDomainName() == "example.com"
    context.getHttpMethod() == "GET"
    context.getPath() == "/foo"
    context.getStage() == "prod"
  }

  def "test context validity for each field"() {
    given:
    def context = new InferredProxyContext()

    when:
    if (proxyName) context.setProxyName("proxy")
    if (startTime) context.setStartTime("123")
    if (domainName) context.setDomainName("domain")
    if (httpMethod) context.setHttpMethod("GET")
    if (path) context.setPath("/path")
    if (stage) context.setStage("prod")

    then:
    context.validContext() == expected

    where:
    proxyName | startTime | domainName | httpMethod | path   | stage  | expected
    false     | true      | true       | true       | true   | true   | false
    true      | false     | true       | true       | true   | true   | false
    true      | true      | false      | true       | true   | true   | false
    true      | true      | true       | false      | true   | true   | false
    true      | true      | true       | true       | false  | true   | false
    true      | true      | true       | true       | true   | false  | false
    true      | true      | true       | true       | true   | true   | true
  }
}
