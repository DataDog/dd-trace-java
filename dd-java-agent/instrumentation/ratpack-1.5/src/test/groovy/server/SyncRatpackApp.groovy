package server

import datadog.appsec.api.blocking.Blocking
import ratpack.form.Form
import ratpack.groovy.test.embed.GroovyEmbeddedApp
import ratpack.handling.HandlerDecorator
import ratpack.test.embed.EmbeddedApp
import static ratpack.jackson.Jackson.json

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.BODY_JSON
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.BODY_MULTIPART
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
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.USER_BLOCK
import static datadog.trace.agent.test.base.HttpServerTest.controller

enum SyncRatpackApp implements EmbeddedApp {
  INSTANCE

  @Delegate
  final EmbeddedApp delegate = GroovyEmbeddedApp.ratpack {
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
            request.body.then {
              typedData ->
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
            context.parse(Form).then {
              form ->
              def text = form.findAll {
                it.key != 'ignore'
              }
              .collectEntries {
                [it.key, it.value as List]
              } as String
              response.status(BODY_URLENCODED.status).send('text/plain', text)
            }
          }
        }
      }
      prefix(BODY_MULTIPART.relativeRawPath()) {
        all {
          controller(BODY_MULTIPART) {
            context.parse(Form).then {
              form ->
              def text = form.collectEntries {
                [it.key, it.value as List]
              } as String
              response.status(BODY_MULTIPART.status).send('text/plain', text)
            }
          }
        }
      }
      prefix(BODY_JSON.relativeRawPath()) {
        all {
          controller(BODY_JSON) {
            context.parse(Map).then {
              map -> {
                response.status(BODY_JSON.status)
                context.render(json(map))
              }
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
      prefix(USER_BLOCK.relativeRawPath()) {
        all {
          controller(USER_BLOCK) {
            Blocking.forUser('user-to-block').blockIfMatch()
            context.response.status(SUCCESS.status).send('should never be reached')
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
  }
}

