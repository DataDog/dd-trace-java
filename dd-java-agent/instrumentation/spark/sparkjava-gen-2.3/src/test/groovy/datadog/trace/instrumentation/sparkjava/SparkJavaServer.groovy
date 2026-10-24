package datadog.trace.instrumentation.sparkjava

import datadog.appsec.api.blocking.Blocking
import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest

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

class SparkJavaServer implements HttpServer {
  final int port

  SparkJavaServer(int port) {
    this.port = port
  }

  @Override
  void start() {
    spark.Spark.port(port)

    spark.Spark.get(SUCCESS.path) { req, res ->
      controller(SUCCESS) {
        res.status(SUCCESS.status)
        SUCCESS.body
      }
    }

    spark.Spark.get(FORWARDED.path) { req, res ->
      controller(FORWARDED) {
        res.status(FORWARDED.status)
        req.headers("x-forwarded-for")
      }
    }

    spark.Spark.get(QUERY_PARAM.rawPath) { req, res ->
      controller(QUERY_PARAM) {
        res.status(QUERY_PARAM.status)
        QUERY_PARAM.bodyForQuery(req.queryString())
      }
    }

    // SparkJava decodes the URL path before route matching, so use decoded path
    spark.Spark.get(QUERY_ENCODED_BOTH.path) { req, res ->
      controller(QUERY_ENCODED_BOTH) {
        res.status(QUERY_ENCODED_BOTH.status)
        QUERY_ENCODED_BOTH.bodyForQuery(req.queryString())
      }
    }

    spark.Spark.get(QUERY_ENCODED_QUERY.rawPath) { req, res ->
      controller(QUERY_ENCODED_QUERY) {
        res.status(QUERY_ENCODED_QUERY.status)
        QUERY_ENCODED_QUERY.bodyForQuery(req.queryString())
      }
    }

    spark.Spark.get(REDIRECT.path) { req, res ->
      controller(REDIRECT) {
        res.redirect(REDIRECT.body, REDIRECT.status)
        null
      }
    }

    spark.Spark.get(ERROR.path) { req, res ->
      controller(ERROR) {
        res.status(ERROR.status)
        ERROR.body
      }
    }

    spark.Spark.get(EXCEPTION.path) { req, res ->
      controller(EXCEPTION) {
        throw new Exception(EXCEPTION.body)
      }
    }

    spark.Spark.get(USER_BLOCK.path) { req, res ->
      controller(USER_BLOCK) {
        Blocking.forUser('user-to-block').blockIfMatch()
        res.status(SUCCESS.status)
        'should never be reached'
      }
    }

    spark.Spark.get("/path/:id/param") { req, res ->
      controller(PATH_PARAM) {
        res.status(PATH_PARAM.status)
        req.params(":id")
      }
    }

    spark.Spark.post(CREATED.path) { req, res ->
      controller(CREATED) {
        res.status(CREATED.status)
        "${CREATED.body}: ${req.body()}"
      }
    }

    spark.Spark.post(BODY_URLENCODED.rawPath) { req, res ->
      controller(BODY_URLENCODED) {
        res.status(BODY_URLENCODED.status)
        // Parse the url-encoded form body
        def params = [:]
        req.body().split("&").each { pair ->
          def kv = pair.split("=", 2)
          if (kv.length == 2 && kv[0] != 'ignore') {
            def key = URLDecoder.decode(kv[0], "UTF-8")
            def value = URLDecoder.decode(kv[1], "UTF-8")
            if (!params.containsKey(key)) {
              params[key] = []
            }
            params[key] << value
          }
        }
        params as String
      }
    }

    spark.Spark.exception(Exception) { exception, req, res ->
      res.status(500)
      res.body(exception.message)
    }

    spark.Spark.after { req, res ->
      res.header(HttpServerTest.IG_RESPONSE_HEADER, HttpServerTest.IG_RESPONSE_HEADER_VALUE)
      res.header("x-datadog-test-request-header", "request_header_value")
    }

    spark.Spark.awaitInitialization()
  }

  @Override
  void stop() {
    spark.Spark.stop()
    // awaitStop() is not available in SparkJava 2.3, sleep briefly to allow shutdown
    Thread.sleep(500)
  }

  @Override
  URI address() {
    return new URI("http://localhost:${port}/")
  }
}
