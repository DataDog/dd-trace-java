package server


import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.bootstrap.instrumentation.api.URIUtils
import datadog.trace.instrumentation.netty41.server.NettyHttpServerDecorator
import datadog.trace.instrumentation.ratpack.RatpackServerDecorator
import ratpack.error.ServerErrorHandler
import ratpack.form.Form
import ratpack.groovy.test.embed.GroovyEmbeddedApp
import ratpack.handling.Context
import ratpack.handling.HandlerDecorator
import ratpack.test.embed.EmbeddedApp

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.BODY_JSON
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.BODY_URLENCODED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.CREATED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.ERROR
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.FORWARDED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.PATH_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_ENCODED_BOTH
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_ENCODED_QUERY
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.REDIRECT
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS

class RatpackHttpServerTest extends HttpServerTest<EmbeddedApp> {

  @Override
  HttpServer server() {
    return new RatpackServer(GroovyEmbeddedApp.ratpack {
      serverConfig {
        port 0
        address InetAddress.getByName('localhost')
      }
      bindings {
        bind TestErrorHandler
        multiBindInstance(HandlerDecorator, HandlerDecorator.prepend(new ResponseHeaderDecorator()))
      }
      handlers {
        prefix(SUCCESS.relativeRawPath()) {
          all {
            controller(SUCCESS) {
              context.response.status(SUCCESS.status).send(SUCCESS.body)
            }
          }
        }
        prefix(CREATED.relativeRawPath()) {
          all {
            controller(CREATED) {
              request.body.then { typedData ->
                response.status(CREATED.status)
                  .send('text/plain', "${CREATED.body}: ${typedData.text}")
              }
            }
          }
        }
        get('path/:id/param') {
          controller(PATH_PARAM) {
            context.response.status(PATH_PARAM.status).send('text/plain', context.pathTokens['id'])
          }
        }
        prefix(BODY_URLENCODED.relativeRawPath()) {
          all {
            controller(BODY_URLENCODED) {
              context.parse(Form).then { form ->
                def text = form.findAll { it.key != 'ignore'}
                .collectEntries {[it.key, it.value as List]} as String
                response.status(BODY_URLENCODED.status).send('text/plain', text)
              }
            }
          }
        }
        prefix(BODY_JSON.relativeRawPath()) {
          all {
            controller(BODY_JSON) {
              context.parse(Map).then { map ->
                response.status(BODY_JSON.status).send('text/plain', "{\"a\":\"${map['a']}\"}")
              }
            }
          }
        }
        prefix(FORWARDED.relativeRawPath()) {
          all {
            controller(FORWARDED) {
              context.response.status(FORWARDED.status).send(request.headers.get("x-forwarded-for"))
            }
          }
        }
        prefix(QUERY_ENCODED_BOTH.relativeRawPath()) {
          all {
            controller(QUERY_ENCODED_BOTH) {
              context.response.status(QUERY_ENCODED_BOTH.status).send(QUERY_ENCODED_BOTH.bodyForQuery(request.query))
            }
          }
        }
        prefix(QUERY_ENCODED_QUERY.relativeRawPath()) {
          all {
            controller(QUERY_ENCODED_QUERY) {
              context.response.status(QUERY_ENCODED_QUERY.status).send(QUERY_ENCODED_QUERY.bodyForQuery(request.query))
            }
          }
        }
        prefix(QUERY_PARAM.relativeRawPath()) {
          all {
            controller(QUERY_PARAM) {
              context.response.status(QUERY_PARAM.status).send(QUERY_PARAM.bodyForQuery(request.query))
            }
          }
        }
        prefix(REDIRECT.relativeRawPath()) {
          all {
            controller(REDIRECT) {
              context.redirect(REDIRECT.body)
            }
          }
        }
        prefix(ERROR.relativeRawPath()) {
          all {
            controller(ERROR) {
              context.response.status(ERROR.status).send(ERROR.body)
            }
          }
        }
        prefix(EXCEPTION.relativeRawPath()) {
          all {
            controller(EXCEPTION) {
              throw new Exception(EXCEPTION.body)
            }
          }
        }
      }
    })
  }

  static class TestErrorHandler implements ServerErrorHandler {
    @Override
    void error(Context context, Throwable throwable) throws Exception {
      context.response.status(500).send(throwable.message)
    }
  }

  @Override
  String component() {
    return NettyHttpServerDecorator.DECORATE.component()
  }

  @Override
  String expectedOperationName() {
    "netty.request"
  }

  @Override
  String expectedResourceName(ServerEndpoint endpoint, String method, URI address) {
    if (endpoint == PATH_PARAM) {
      'GET /path/:id/param'
    } else {
      super.expectedResourceName(endpoint, method, address)
    }
  }

  @Override
  Map<String, ?> expectedIGPathParams() {
    [id: '123']
  }

  @Override
  boolean hasHandlerSpan() {
    true
  }

  @Override
  boolean testRequestBody() {
    true
  }

  @Override
  boolean testBodyUrlencoded() {
    true
  }

  @Override
  boolean testBodyJson() {
    true
  }

  @Override
  String testPathParam() {
    true
  }

  @Override
  boolean testNotFound() {
    // resource name is set by instrumentation, so not changed to 404
    false
  }

  @Override
  Serializable expectedServerSpanRoute(ServerEndpoint endpoint) {
    return String
  }

  @Override
  boolean hasDecodedResource() {
    false
  }

  @Override
  void handlerSpan(TraceAssert trace, ServerEndpoint endpoint = SUCCESS) {
    trace.span {
      serviceName expectedServiceName()
      operationName "ratpack.handler"
      spanType DDSpanTypes.HTTP_SERVER
      errored endpoint == ERROR || endpoint == EXCEPTION
      childOfPrevious()
      tags {
        "$Tags.COMPONENT" RatpackServerDecorator.DECORATE.component()
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
        "$Tags.PEER_HOST_IPV4" "127.0.0.1" // This span ignores "x-forwards-from".
        "$Tags.PEER_PORT" Integer
        "$Tags.HTTP_URL" String
        "$Tags.HTTP_HOSTNAME" "${address.host}"
        "$Tags.HTTP_METHOD" String
        "$Tags.HTTP_STATUS" Integer
        "$Tags.HTTP_ROUTE" String
        if (endpoint == EXCEPTION) {
          errorTags(Exception, EXCEPTION.body)
        }
        if (endpoint.query) {
          "$DDTags.HTTP_QUERY"  URIUtils.decode(endpoint.rawQuery)
        }
        defaultTags()
      }
    }
  }
}
