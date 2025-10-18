import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.base.OkHttpWebsocketClient
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.bootstrap.instrumentation.api.URIUtils
import datadog.trace.core.DDSpan
import dd.trace.instrumentation.springwebflux.server.EchoHandlerFunction
import dd.trace.instrumentation.springwebflux.server.FooModel
import dd.trace.instrumentation.springwebflux.server.SpringWebFluxTestApplication
import dd.trace.instrumentation.springwebflux.server.TestController
import dd.trace.instrumentation.springwebflux.server.WsHandler
import net.bytebuddy.utility.RandomString
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.web.embedded.netty.NettyReactiveWebServerFactory
import org.springframework.boot.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.BodyExtractors
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Mono

import static datadog.trace.agent.test.base.HttpServerTest.websocketCloseSpan
import static datadog.trace.agent.test.base.HttpServerTest.websocketReceiveSpan
import static datadog.trace.agent.test.base.HttpServerTest.websocketSendSpan

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
classes = [SpringWebFluxTestApplication, ForceNettyAutoConfiguration],
properties = "server.http2.enabled=true")
class SpringWebfluxTest extends InstrumentationSpecification {

  @TestConfiguration
  static class ForceNettyAutoConfiguration {
    @Bean
    NettyReactiveWebServerFactory nettyFactory() {
      return new NettyReactiveWebServerFactory()
    }
  }

  static final String INNER_HANDLER_FUNCTION_CLASS_TAG_PREFIX = SpringWebFluxTestApplication.getName() + "\$"
  static final String SPRING_APP_CLASS_ANON_NESTED_CLASS_PREFIX = SpringWebFluxTestApplication.getSimpleName() + "\$"

  @LocalServerPort
  int port

  @Autowired
  private WsHandler wsHandler

  WebClient client = WebClient.builder().clientConnector(new ReactorClientHttpConnector()).build()

  @Override
  boolean useStrictTraceWrites() {
    false
  }

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("trace.websocket.messages.enabled", "true")
  }

  def "Basic GET test #testName"() {
    setup:
    String url = "http://localhost:$port$urlPath"
    when:
    def response = client.get().uri(url).exchange().block()

    then:
    response.statusCode().value() == 200
    response.body(BodyExtractors.toMono(String)).block() == expectedResponseBody
    assertTraces(2) {
      def traceParent
      sortSpansByStart()
      trace(2) {
        clientSpan(it, null, "http.request", "spring-webflux-client", "GET", URI.create(url))
        traceParent = clientSpan(it, span(0), "netty.client.request", "netty-client", "GET", URI.create(url))
      }
      trace(2) {
        span {
          resourceName "GET $urlPathWithVariables"
          operationName "netty.request"
          spanType DDSpanTypes.HTTP_SERVER
          childOf(traceParent)
          tags {
            "$Tags.COMPONENT" "netty"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
            "$Tags.PEER_HOST_IPV4" "127.0.0.1"
            "$Tags.PEER_PORT" Integer
            "$Tags.HTTP_URL" url
            "$Tags.HTTP_HOSTNAME" "localhost"
            "$Tags.HTTP_METHOD" "GET"
            "$Tags.HTTP_STATUS" 200
            "$Tags.HTTP_USER_AGENT" String
            "$Tags.HTTP_CLIENT_IP" "127.0.0.1"
            "$Tags.HTTP_ROUTE" "$urlPathWithVariables"
            defaultTags(true)
          }
        }
        span {
          if (annotatedMethod == null) {
            // Functional API
            resourceNameContains(SPRING_APP_CLASS_ANON_NESTED_CLASS_PREFIX, ".handle")
            operationNameContains(SPRING_APP_CLASS_ANON_NESTED_CLASS_PREFIX, ".handle")
          } else {
            // Annotation API
            resourceName TestController.getSimpleName() + "." + annotatedMethod
            operationName TestController.getSimpleName() + "." + annotatedMethod
          }
          spanType DDSpanTypes.HTTP_SERVER
          childOf(span(0))
          tags {
            "$Tags.COMPONENT" "spring-webflux-controller"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
            if (annotatedMethod == null) {
              // Functional API
              "request.predicate" "(GET && $urlPathWithVariables)"
              "handler.type" { String tagVal ->
                return tagVal.contains(INNER_HANDLER_FUNCTION_CLASS_TAG_PREFIX)
              }
            } else {
              // Annotation API
              "handler.type" TestController.getName()
            }
            defaultTags()
          }
        }
      }
    }

    where:
    testName                             | urlPath              | urlPathWithVariables   | annotatedMethod | expectedResponseBody
    "functional API without parameters"  | "/greet"             | "/greet"               | null            | SpringWebFluxTestApplication.GreetingHandler.DEFAULT_RESPONSE
    "functional API with one parameter"  | "/greet/WORLD"       | "/greet/{name}"        | null            | SpringWebFluxTestApplication.GreetingHandler.DEFAULT_RESPONSE + " WORLD"
    "functional API with two parameters" | "/greet/World/Test1" | "/greet/{name}/{word}" | null            | SpringWebFluxTestApplication.GreetingHandler.DEFAULT_RESPONSE + " World Test1"
    "functional API delayed response"    | "/greet-delayed"     | "/greet-delayed"       | null            | SpringWebFluxTestApplication.GreetingHandler.DEFAULT_RESPONSE

    "annotation API without parameters"  | "/foo"               | "/foo"                 | "getFooModel"   | new FooModel(0L, "DEFAULT").toString()
    "annotation API with one parameter"  | "/foo/1"             | "/foo/{id}"            | "getFooModel"   | new FooModel(1L, "pass").toString()
    "annotation API with two parameters" | "/foo/2/world"       | "/foo/{id}/{name}"     | "getFooModel"   | new FooModel(2L, "world").toString()
    "annotation API delayed response"    | "/foo-delayed"       | "/foo-delayed"         | "getFooDelayed" | new FooModel(3L, "delayed").toString()
  }

  def "GET test with async response #testName"() {
    setup:
    String url = "http://localhost:$port$urlPath"
    when:
    def response = client.get().uri(url).exchange().block()

    then:
    response.statusCode().value() == 200
    response.body(BodyExtractors.toMono(String)).block() == expectedResponseBody
    assertTraces(2) {
      sortSpansByStart()
      def traceParent
      trace(2) {
        clientSpan(it, null, "http.request", "spring-webflux-client", "GET", URI.create(url))
        traceParent = clientSpan(it, span(0), "netty.client.request", "netty-client", "GET", URI.create(url))
      }
      trace(3) {
        span {
          resourceName "GET $urlPathWithVariables"
          operationName "netty.request"
          spanType DDSpanTypes.HTTP_SERVER
          childOf(traceParent)
          tags {
            "$Tags.COMPONENT" "netty"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
            "$Tags.PEER_HOST_IPV4" "127.0.0.1"
            "$Tags.PEER_PORT" Integer
            "$Tags.HTTP_URL" url
            "$Tags.HTTP_HOSTNAME" "localhost"
            "$Tags.HTTP_METHOD" "GET"
            "$Tags.HTTP_STATUS" 200
            "$Tags.HTTP_USER_AGENT" String
            "$Tags.HTTP_CLIENT_IP" "127.0.0.1"
            "$Tags.HTTP_ROUTE" "$urlPathWithVariables"
            defaultTags(true)
          }
        }
        span {
          if (annotatedMethod == null) {
            // Functional API
            resourceNameContains(SPRING_APP_CLASS_ANON_NESTED_CLASS_PREFIX, ".handle")
            operationNameContains(SPRING_APP_CLASS_ANON_NESTED_CLASS_PREFIX, ".handle")
          } else {
            // Annotation API
            resourceName TestController.getSimpleName() + "." + annotatedMethod
            operationName TestController.getSimpleName() + "." + annotatedMethod
          }
          spanType DDSpanTypes.HTTP_SERVER
          childOf(span(0))
          tags {
            "$Tags.COMPONENT" "spring-webflux-controller"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
            if (annotatedMethod == null) {
              // Functional API
              "request.predicate" "(GET && $urlPathWithVariables)"
              "handler.type" { String tagVal ->
                return tagVal.contains(INNER_HANDLER_FUNCTION_CLASS_TAG_PREFIX)
              }
            } else {
              // Annotation API
              "handler.type" TestController.getName()
            }
            defaultTags()
          }
        }
        span {
          if (annotatedMethod == null) {
            // Functional API
            resourceName "SpringWebFluxTestApplication.tracedMethod"
            operationName "trace.annotation"
          } else {
            // Annotation API
            resourceName "TestController.tracedMethod"
            operationName "trace.annotation"
          }
          childOfPrevious()
          errored false
          tags {
            "$Tags.COMPONENT" "trace"
            defaultTags()
          }
        }
      }
    }

    where:
    testName                                  | urlPath                       | urlPathWithVariables             | annotatedMethod       | expectedResponseBody
    "functional API traced method from mono"  | "/greet-mono-from-callable/4" | "/greet-mono-from-callable/{id}" | null                  | SpringWebFluxTestApplication.GreetingHandler.DEFAULT_RESPONSE + " 4"
    "functional API traced method with delay" | "/greet-delayed-mono/6"       | "/greet-delayed-mono/{id}"       | null                  | SpringWebFluxTestApplication.GreetingHandler.DEFAULT_RESPONSE + " 6"

    "annotation API traced method from mono"  | "/foo-mono-from-callable/7"   | "/foo-mono-from-callable/{id}"   | "getMonoFromCallable" | new FooModel(7L, "tracedMethod").toString()
    "annotation API traced method with delay" | "/foo-delayed-mono/9"         | "/foo-delayed-mono/{id}"         | "getFooDelayedMono"   | new FooModel(9L, "tracedMethod").toString()
  }


  def "Create span during handler function"() {
    setup:
    String url = "http://localhost:$port$urlPath"
    when:
    def response = client.get().uri(url).exchange().block()

    then:
    response.statusCode().value() == 200
    response.body(BodyExtractors.toMono(String)).block() == expectedResponseBody
    assertTraces(2) {
      sortSpansByStart()
      def traceParent
      trace(2) {
        clientSpan(it, null, "http.request", "spring-webflux-client", "GET", URI.create(url))
        traceParent = clientSpan(it, span(0), "netty.client.request", "netty-client", "GET", URI.create(url))
      }
      trace(3) {
        span {
          resourceName "GET $urlPathWithVariables"
          operationName "netty.request"
          spanType DDSpanTypes.HTTP_SERVER
          childOf(traceParent)
        }
        span {
          if (annotatedMethod == null) {
            // Functional API
            operationNameContains(SPRING_APP_CLASS_ANON_NESTED_CLASS_PREFIX, ".handle")
          } else {
            // Annotation API
            operationName TestController.getSimpleName() + "." + annotatedMethod
          }
          spanType DDSpanTypes.HTTP_SERVER
          childOfPrevious()
        }
        span {
          operationName "trace.annotation"
          childOfPrevious()
          errored false
        }
      }
    }

    where:
    testName                       | urlPath                  | urlPathWithVariables        | annotatedMethod   | expectedResponseBody
    "functional API traced method" | "/greet-traced-method/5" | "/greet-traced-method/{id}" | null              | SpringWebFluxTestApplication.GreetingHandler.DEFAULT_RESPONSE + " 5"
    "annotation API traced method" | "/foo-traced-method/8"   | "/foo-traced-method/{id}"   | "getTracedMethod" | new FooModel(8L, "tracedMethod").toString()
  }

  def "404 GET test"() {
    setup:
    String url = "http://localhost:$port/notfoundgreet"

    when:
    def response = client.get().uri(url).exchange().block()

    then:
    response.statusCode().value() == 404
    assertTraces(2) {
      sortSpansByStart()
      def traceParent
      trace(2) {
        clientSpan(it, null, "http.request", "spring-webflux-client", "GET", URI.create(url), 404, true)
        traceParent = clientSpan(it, span(0), "netty.client.request", "netty-client", "GET", URI.create(url), 404, true)
      }
      trace(2) {
        span {
          resourceName "404"
          operationName "netty.request"
          spanType DDSpanTypes.HTTP_SERVER
          childOf(traceParent)
          tags {
            "$Tags.COMPONENT" "netty"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
            "$Tags.PEER_HOST_IPV4" "127.0.0.1"
            "$Tags.PEER_PORT" Integer
            "$Tags.HTTP_URL" url
            "$Tags.HTTP_HOSTNAME" "localhost"
            "$Tags.HTTP_METHOD" "GET"
            "$Tags.HTTP_STATUS" 404
            "$Tags.HTTP_USER_AGENT" String
            "$Tags.HTTP_CLIENT_IP" "127.0.0.1"
            defaultTags(true)
          }
        }
        span {
          resourceName "ResourceWebHandler.handle"
          operationName "ResourceWebHandler.handle"
          spanType DDSpanTypes.HTTP_SERVER
          childOf(span(0))
          errored true
          tags {
            "$Tags.COMPONENT" "spring-webflux-controller"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
            "handler.type" "org.springframework.web.reactive.resource.ResourceWebHandler"
            errorTags(ResponseStatusException, String)
            defaultTags()
          }
        }
      }
    }
  }

  def "Basic POST test"() {
    setup:
    String echoString = "TEST"
    String url = "http://localhost:$port/echo"

    when:
    def response = client.post().uri(url).body(BodyInserters.fromPublisher(Mono.just(echoString), String)).exchange().block()

    then:
    response.statusCode().value() == 202
    response.body(BodyExtractors.toMono(String)).block() == echoString
    assertTraces(2) {
      sortSpansByStart()
      def traceParent
      trace(2) {
        clientSpan(it, null, "http.request", "spring-webflux-client", "POST", URI.create(url), 202)
        traceParent = clientSpan(it, span(0), "netty.client.request", "netty-client", "POST", URI.create(url), 202)
      }
      trace(3) {
        span {
          resourceName "POST /echo"
          operationName "netty.request"
          spanType DDSpanTypes.HTTP_SERVER
          childOf(traceParent)
          tags {
            "$Tags.COMPONENT" "netty"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
            "$Tags.PEER_HOST_IPV4" "127.0.0.1"
            "$Tags.PEER_PORT" Integer
            "$Tags.HTTP_URL" url
            "$Tags.HTTP_HOSTNAME" "localhost"
            "$Tags.HTTP_METHOD" "POST"
            "$Tags.HTTP_STATUS" 202
            "$Tags.HTTP_USER_AGENT" String
            "$Tags.HTTP_CLIENT_IP" "127.0.0.1"
            "$Tags.HTTP_ROUTE" "/echo"
            defaultTags(true)
          }
        }
        span {
          resourceName EchoHandlerFunction.getSimpleName() + ".handle"
          operationName EchoHandlerFunction.getSimpleName() + ".handle"
          spanType DDSpanTypes.HTTP_SERVER
          childOf(span(0))
          tags {
            "$Tags.COMPONENT" "spring-webflux-controller"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
            "request.predicate" "(POST && /echo)"
            "handler.type" { String tagVal ->
              return tagVal.contains(EchoHandlerFunction.getName())
            }
            defaultTags()
          }
        }
        span {
          resourceName "echo"
          operationName "echo"
          childOf(span(1))
          tags {
            "$Tags.COMPONENT" "trace"
            defaultTags()
          }
        }
      }
    }
  }

  def "GET to bad endpoint #testName"() {
    setup:
    String url = "http://localhost:$port$urlPath"

    when:
    def response = client.get().uri(url).exchange().block()

    then:
    response.statusCode().value() == 500
    assertTraces(2) {
      sortSpansByStart()
      def traceParent
      trace(2) {
        clientSpan(it, null, "http.request", "spring-webflux-client", "GET", URI.create(url), 500)
        traceParent = clientSpan(it, span(0), "netty.client.request", "netty-client", "GET", URI.create(url), 500)
      }
      trace(2) {
        span {
          resourceName "GET $urlPathWithVariables"
          operationName "netty.request"
          spanType DDSpanTypes.HTTP_SERVER
          errored true
          childOf(traceParent)
          tags {
            "$Tags.COMPONENT" "netty"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
            "$Tags.PEER_HOST_IPV4" "127.0.0.1"
            "$Tags.PEER_PORT" Integer
            "$Tags.HTTP_URL" url
            "$Tags.HTTP_HOSTNAME" "localhost"
            "$Tags.HTTP_METHOD" "GET"
            "$Tags.HTTP_STATUS" 500
            "$Tags.HTTP_USER_AGENT" String
            "$Tags.HTTP_CLIENT_IP" "127.0.0.1"
            "$Tags.HTTP_ROUTE" "$urlPathWithVariables"
            defaultTags(true)
          }
        }
        span {
          if (annotatedMethod == null) {
            // Functional API
            resourceNameContains(SPRING_APP_CLASS_ANON_NESTED_CLASS_PREFIX, ".handle")
            operationNameContains(SPRING_APP_CLASS_ANON_NESTED_CLASS_PREFIX, ".handle")
          } else {
            // Annotation API
            resourceName TestController.getSimpleName() + "." + annotatedMethod
            operationName TestController.getSimpleName() + "." + annotatedMethod
          }
          spanType DDSpanTypes.HTTP_SERVER
          childOf(span(0))
          errored true
          tags {
            "$Tags.COMPONENT" "spring-webflux-controller"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
            if (annotatedMethod == null) {
              // Functional API
              "request.predicate" "(GET && $urlPathWithVariables)"
              "handler.type" { String tagVal ->
                return tagVal.contains(INNER_HANDLER_FUNCTION_CLASS_TAG_PREFIX)
              }
            } else {
              // Annotation API
              "handler.type" TestController.getName()
            }
            errorTags(RuntimeException, "bad things happen")
            defaultTags()
          }
        }
      }
    }

    where:
    testName                   | urlPath             | urlPathWithVariables   | annotatedMethod
    "functional API fail fast" | "/greet-failfast/1" | "/greet-failfast/{id}" | null
    "functional API fail Mono" | "/greet-failmono/1" | "/greet-failmono/{id}" | null

    "annotation API fail fast" | "/foo-failfast/1"   | "/foo-failfast/{id}"   | "getFooFailFast"
    "annotation API fail Mono" | "/foo-failmono/1"   | "/foo-failmono/{id}"   | "getFooFailMono"
  }

  def "Redirect test"() {
    setup:
    String url = "http://localhost:$port/double-greet-redirect"
    String finalUrl = "http://localhost:$port/double-greet"

    when:
    def response = client.get().uri(url).exchange()
    .flatMap(response -> {
      if (response.statusCode().is3xxRedirection()) {
        String redirectUrl = response.headers().header("Location").get(0)
        return response.bodyToMono(Void).then(client.get().uri(URI.create("http://localhost:$port").resolve(redirectUrl)).exchange())
      }
      return Mono.just(response)
    }).block()

    then:
    response.statusCode().value() == 200
    assertTraces(4, SORT_TRACES_BY_START) {
      def traceParent1, traceParent2

      trace(2) {
        sortSpansByStart()
        clientSpan(it, null, "http.request", "spring-webflux-client", "GET", URI.create(url), 307)
        traceParent1 = clientSpan(it, span(0), "netty.client.request", "netty-client", "GET", URI.create(url), 307)
      }

      trace(2) {
        sortSpansByStart()
        span {
          resourceName "GET /double-greet-redirect"
          operationName "netty.request"
          spanType DDSpanTypes.HTTP_SERVER
          childOf traceParent1
          tags {
            "$Tags.COMPONENT" "netty"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
            "$Tags.PEER_HOST_IPV4" "127.0.0.1"
            "$Tags.PEER_PORT" Integer
            "$Tags.HTTP_URL" url
            "$Tags.HTTP_HOSTNAME" "localhost"
            "$Tags.HTTP_METHOD" "GET"
            "$Tags.HTTP_STATUS" 307
            "$Tags.HTTP_USER_AGENT" String
            "$Tags.HTTP_CLIENT_IP" "127.0.0.1"
            "$Tags.HTTP_ROUTE" "/double-greet-redirect"
            defaultTags(true)
          }
        }
        span {
          resourceName "RedirectComponent.lambda"
          operationName "RedirectComponent.lambda"
          spanType DDSpanTypes.HTTP_SERVER
          childOf(span(0))
          tags {
            "$Tags.COMPONENT" "spring-webflux-controller"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
            "request.predicate" "(GET && /double-greet-redirect)"
            "handler.type" {
              String tagVal ->
              return (tagVal.contains(INNER_HANDLER_FUNCTION_CLASS_TAG_PREFIX)
              || tagVal.contains("Lambda"))
            }
            defaultTags()
          }
        }
      }
      trace(2) {
        sortSpansByStart()
        clientSpan(it, null, "http.request", "spring-webflux-client", "GET", URI.create(finalUrl))
        traceParent2 = clientSpan(it, span(0), "netty.client.request", "netty-client", "GET", URI.create(finalUrl))
      }
      trace(2) {
        sortSpansByStart()
        span {
          resourceName "GET /double-greet"
          operationName "netty.request"
          spanType DDSpanTypes.HTTP_SERVER
          childOf traceParent2
          tags {
            "$Tags.COMPONENT" "netty"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
            "$Tags.PEER_HOST_IPV4" "127.0.0.1"
            "$Tags.PEER_PORT" Integer
            "$Tags.HTTP_URL" finalUrl
            "$Tags.HTTP_HOSTNAME" "localhost"
            "$Tags.HTTP_METHOD" "GET"
            "$Tags.HTTP_STATUS" 200
            "$Tags.HTTP_USER_AGENT" String
            "$Tags.HTTP_CLIENT_IP" "127.0.0.1"
            "$Tags.HTTP_ROUTE" "/double-greet"
            defaultTags(true)
          }
        }
        span {
          resourceNameContains(SpringWebFluxTestApplication.getSimpleName() + "\$", ".handle")
          operationNameContains(SpringWebFluxTestApplication.getSimpleName() + "\$", ".handle")
          spanType DDSpanTypes.HTTP_SERVER
          childOf(span(0))
          tags {
            "$Tags.COMPONENT" "spring-webflux-controller"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
            "request.predicate" "(GET && /double-greet)"
            "handler.type" {
              String tagVal ->
              return tagVal.contains(INNER_HANDLER_FUNCTION_CLASS_TAG_PREFIX)
            }
            defaultTags()
          }
        }
      }
    }
  }

  def "Multiple GETs to delaying route #testName"() {
    setup:
    def requestsCount = 50 // Should be more than 2x CPUs to fish out some bugs
    String url = "http://localhost:$port$urlPath"
    when:
    def responses = (0..requestsCount - 1).collect { client.get().uri(url).exchange().block() }

    then:
    responses.every { it.statusCode().value() == 200 }
    responses.every { it.body(BodyExtractors.toMono(String)).block() == expectedResponseBody }
    assertTraces(responses.size() * 2) {
      sortSpansByStart()
      responses.eachWithIndex { def response, int i ->
        def traceParent
        trace(2) {
          clientSpan(it, null, "http.request", "spring-webflux-client", "GET", URI.create(url))
          traceParent = clientSpan(it, span(0), "netty.client.request", "netty-client", "GET", URI.create(url))
        }
        trace(2) {
          span {
            resourceName "GET $urlPathWithVariables"
            operationName "netty.request"
            spanType DDSpanTypes.HTTP_SERVER
            childOf(traceParent)
            tags {
              "$Tags.COMPONENT" "netty"
              "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
              "$Tags.PEER_HOST_IPV4" "127.0.0.1"
              "$Tags.PEER_PORT" Integer
              "$Tags.HTTP_URL" url
              "$Tags.HTTP_HOSTNAME" "localhost"
              "$Tags.HTTP_METHOD" "GET"
              "$Tags.HTTP_STATUS" 200
              "$Tags.HTTP_USER_AGENT" String
              "$Tags.HTTP_CLIENT_IP" "127.0.0.1"
              "$Tags.HTTP_ROUTE" "$urlPathWithVariables"
              defaultTags(true)
            }
          }
          span {
            if (annotatedMethod == null) {
              // Functional API
              resourceNameContains(SPRING_APP_CLASS_ANON_NESTED_CLASS_PREFIX, ".handle")
              operationNameContains(SPRING_APP_CLASS_ANON_NESTED_CLASS_PREFIX, ".handle")
            } else {
              // Annotation API
              resourceName TestController.getSimpleName() + "." + annotatedMethod
              operationName TestController.getSimpleName() + "." + annotatedMethod
            }
            spanType DDSpanTypes.HTTP_SERVER
            childOf(span(0))
            tags {
              "$Tags.COMPONENT" "spring-webflux-controller"
              "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
              if (annotatedMethod == null) {
                // Functional API
                "request.predicate" "(GET && $urlPathWithVariables)"
                "handler.type" { String tagVal ->
                  return tagVal.contains(INNER_HANDLER_FUNCTION_CLASS_TAG_PREFIX)
                }
              } else {
                // Annotation API
                "handler.type" TestController.getName()
              }
              defaultTags()
            }
          }
        }
      }
    }

    where:
    testName                          | urlPath          | urlPathWithVariables | annotatedMethod | expectedResponseBody
    "functional API delayed response" | "/greet-delayed" | "/greet-delayed"     | null            | SpringWebFluxTestApplication.GreetingHandler.DEFAULT_RESPONSE
    "annotation API delayed response" | "/foo-delayed"   | "/foo-delayed"       | "getFooDelayed" | new FooModel(3L, "delayed").toString()
  }

  def 'test websocket server receive #msgType message of size #size and #chunks chunks'() {
    when:
    String url = "http://localhost:$port/websocket"
    def wsClient = new OkHttpWebsocketClient()
    wsClient.connect(url)
    wsHandler.awaitConnected()
    if (message instanceof String) {
      wsClient.send(message as String)
    } else {
      wsClient.send(message as byte[])
    }
    wsHandler.awaitExchangeComplete()
    wsClient.close(1001, "goodbye")

    then:
    assertTraces(3, {
      DDSpan handshake
      trace(2) {
        sortSpansByStart()
        handshake = span(0)
        span {
          resourceName "GET /websocket"
          operationName "netty.request"
          spanType DDSpanTypes.HTTP_SERVER
          tags {
            "$Tags.COMPONENT" "netty"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
            "$Tags.PEER_HOST_IPV4" "127.0.0.1"
            "$Tags.PEER_PORT" Integer
            "$Tags.HTTP_URL" url
            "$Tags.HTTP_HOSTNAME" "localhost"
            "$Tags.HTTP_METHOD" "GET"
            "$Tags.HTTP_STATUS" 101
            "$Tags.HTTP_USER_AGENT" String
            "$Tags.HTTP_CLIENT_IP" "127.0.0.1"
            "$Tags.HTTP_ROUTE" "/websocket"
            defaultTags()
          }
        }
        span {
          resourceName "WsHandler.handle"
          operationName "WsHandler.handle"
          spanType DDSpanTypes.HTTP_SERVER
          childOfPrevious()
          tags {
            "$Tags.COMPONENT" "spring-webflux-controller"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
            "handler.type" WsHandler.getName()
            defaultTags()
          }
        }
      }
      trace(2) {
        sortSpansByStart()
        websocketReceiveSpan(it, handshake, msgType, size, chunks)
        websocketSendSpan(it, handshake, msgType, size, chunks)
      }
      trace(1) {
        websocketCloseSpan(it, handshake, false, 1001, "goodbye")
      }
    })
    where:
    message                                 | msgType  | chunks | size
    RandomString.make(10)                   | "text"   | 1      | 10
    RandomString.make(20).getBytes("UTF-8") | "binary" | 1      | 20
  }

  def clientSpan(
  TraceAssert trace,
  Object parentSpan,
  String operation,
  String component,
  String method = "GET",
  URI uri,
  Integer status = 200,
  boolean error = false,
  Throwable exception = null,
  boolean tagQueryString = false,
  Map<String, Serializable> extraTags = null) {
    def ret

    def expectedQuery = tagQueryString ? uri.query : null
    def expectedUrl = URIUtils.buildURL(uri.scheme, uri.host, uri.port, uri.path)
    if (expectedQuery != null && !expectedQuery.empty) {
      expectedUrl = "$expectedUrl?$expectedQuery"
    }
    trace.span {
      ret = it.span
      if (parentSpan == null) {
        parent()
      } else {
        childOf((DDSpan) parentSpan)
      }
      operationName operation
      spanType DDSpanTypes.HTTP_CLIENT
      errored error
      measured true
      tags {
        "$Tags.COMPONENT" component
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
        "$Tags.PEER_HOSTNAME" uri.host
        "$Tags.PEER_HOST_IPV4" { it == null || it == "127.0.0.1" } // Optional
        "$Tags.PEER_PORT" { it == null || it == uri.port }
        "$Tags.HTTP_URL" expectedUrl
        "$Tags.HTTP_METHOD" method
        if (status) {
          "$Tags.HTTP_STATUS" status
        }
        if (tagQueryString) {
          "$DDTags.HTTP_QUERY" expectedQuery
          "$DDTags.HTTP_FRAGMENT" { it == null || it == uri.fragment } // Optional
        }
        if (exception) {
          errorTags(exception.class, exception.message)
        }
        peerServiceFrom(Tags.PEER_HOSTNAME)
        defaultTags()
        if (extraTags) {
          it.addTags(extraTags)
        }
      }
    }
    return ret
  }
}
