package server

import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import ratpack.exec.Promise
import ratpack.form.Form
import ratpack.groovy.test.embed.GroovyEmbeddedApp
import ratpack.handling.HandlerDecorator

import java.nio.charset.StandardCharsets

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

class RatpackForkedHttpServerTest extends RatpackHttpServerTest {

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
            Promise.sync {
              SUCCESS
            }.fork().then { HttpServerTest.ServerEndpoint endpoint ->
              controller(endpoint) {
                context.response.status(endpoint.status).send(endpoint.body)
              }
            }
          }
        }
        prefix(CREATED.relativeRawPath()) {
          all {
            Promise.sync {
              CREATED
            }.fork().then { endpoint ->
              request.body.then { typedData ->
                response.status(endpoint.status)
                  .send(
                  'text/plain',
                  "${endpoint.body}: ${new String(typedData.bytes, StandardCharsets.UTF_8)}")
              }
            }
          }
        }
        prefix(FORWARDED.relativeRawPath()) {
          all {
            Promise.sync {
              FORWARDED
            }.fork().then { HttpServerTest.ServerEndpoint endpoint ->
              controller(endpoint) {
                context.response.status(endpoint.status).send(request.headers.get("x-forwarded-for"))
              }
            }
          }
        }
        get('path/:id/param') {
          Promise.sync {
            PATH_PARAM
          }.fork().then {endpoint ->
            controller(endpoint) {
              context.response.status(PATH_PARAM.status).send('text/plain', context.pathTokens['id'])
            }
          }
        }
        prefix(BODY_URLENCODED.relativeRawPath()) {
          all {
            Promise.sync {
              BODY_URLENCODED
            }.fork().then { endpoint ->
              controller(BODY_URLENCODED) {
                context.parse(Form).then { form ->
                  def text = form.findAll { it.key != 'ignore' }
                  .collectEntries { [it.key, it.value as List] } as String
                  response.status(BODY_URLENCODED.status).send('text/plain', text)
                }
              }
            }
          }
        }
        prefix(BODY_JSON.relativeRawPath()) {
          all {
            Promise.sync {
              BODY_JSON
            }.fork().then {endpoint ->
              controller(endpoint) {
                context.parse(Map).then { map ->
                  response.status(BODY_JSON.status).send('text/plain', "{\"a\":\"${map['a']}\"}")
                }
              }
            }
          }
        }
        prefix(QUERY_ENCODED_BOTH.relativeRawPath()) {
          all {
            Promise.sync {
              QUERY_ENCODED_BOTH
            }.fork().then { HttpServerTest.ServerEndpoint endpoint ->
              controller(endpoint) {
                context.response.status(endpoint.status).send(endpoint.bodyForQuery(request.query))
              }
            }
          }
        }
        prefix(QUERY_ENCODED_QUERY.relativeRawPath()) {
          all {
            Promise.sync {
              QUERY_ENCODED_QUERY
            }.fork().then { HttpServerTest.ServerEndpoint endpoint ->
              controller(endpoint) {
                context.response.status(endpoint.status).send(endpoint.bodyForQuery(request.query))
              }
            }
          }
        }
        prefix(QUERY_PARAM.relativeRawPath()) {
          all {
            Promise.sync {
              QUERY_PARAM
            }.fork().then { HttpServerTest.ServerEndpoint endpoint ->
              controller(endpoint) {
                context.response.status(endpoint.status).send(endpoint.bodyForQuery(request.query))
              }
            }
          }
        }
        prefix(REDIRECT.relativeRawPath()) {
          all {
            Promise.sync {
              REDIRECT
            }.fork().then { HttpServerTest.ServerEndpoint endpoint ->
              controller(endpoint) {
                context.redirect(endpoint.body)
              }
            }
          }
        }
        prefix(ERROR.relativeRawPath()) {
          all {
            Promise.sync {
              ERROR
            }.fork().then { HttpServerTest.ServerEndpoint endpoint ->
              controller(endpoint) {
                context.response.status(endpoint.status).send(endpoint.body)
              }
            }
          }
        }
        prefix(EXCEPTION.relativeRawPath()) {
          all {
            Promise.sync {
              EXCEPTION
            }.fork().then { HttpServerTest.ServerEndpoint endpoint ->
              controller(endpoint) {
                throw new Exception(endpoint.body)
              }
            }
          }
        }
      }
    })
  }
}
