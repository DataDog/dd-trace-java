import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import spark.Spark

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.*
import static datadog.trace.agent.test.base.HttpServerTest.IG_RESPONSE_HEADER
import static datadog.trace.agent.test.base.HttpServerTest.IG_RESPONSE_HEADER_VALUE

class SparkJavaTest extends HttpServerTest<Object> {

  class SparkJavaServer implements HttpServer {
    def port = 0

    SparkJavaServer() {
      port = datadog.trace.agent.test.utils.PortUtils.randomOpenPort()
      Spark.port(port)

      Spark.get(SUCCESS.path) { req, res ->
        controller(SUCCESS) {
          res.status(SUCCESS.status)
          SUCCESS.body
        }
      }

      Spark.get(FORWARDED.path) { req, res ->
        controller(FORWARDED) {
          res.status(FORWARDED.status)
          req.headers("x-forwarded-for")
        }
      }

      Spark.get(QUERY_PARAM.path) { req, res ->
        controller(QUERY_PARAM) {
          res.status(QUERY_PARAM.status)
          req.queryString()
        }
      }

      Spark.get(QUERY_ENCODED_BOTH.path) { req, res ->
        controller(QUERY_ENCODED_BOTH) {
          res.status(QUERY_ENCODED_BOTH.status)
          "some=" + req.queryParams("some")
        }
      }

      Spark.get(QUERY_ENCODED_QUERY.path) { req, res ->
        controller(QUERY_ENCODED_QUERY) {
          res.status(QUERY_ENCODED_QUERY.status)
          "some=" + req.queryParams("some")
        }
      }

      Spark.get(REDIRECT.path) { req, res ->
        controller(REDIRECT) {
          res.redirect(REDIRECT.body, REDIRECT.status)
          null
        }
      }

      Spark.get(ERROR.path) { req, res ->
        controller(ERROR) {
          res.status(ERROR.status)
          ERROR.body
        }
      }

      Spark.get(EXCEPTION.path) { req, res ->
        controller(EXCEPTION) {
          throw new Exception(EXCEPTION.body)
        }
      }

      Spark.after { req, res ->
        res.header(IG_RESPONSE_HEADER, IG_RESPONSE_HEADER_VALUE)
      }
    }

    @Override
    void start() {
      Spark.awaitInitialization()
    }

    @Override
    void stop() {
      Spark.stop()
    }

    @Override
    URI address() {
      return new URI("http://localhost:$port/")
    }
  }

  @Override
  HttpServer server() {
    return new SparkJavaServer()
  }

  @Override
  String component() {
    return 'spark-java'
  }

  @Override
  String expectedOperationName() {
    return 'spark.request'
  }

  @Override
  protected boolean enabledFinishTimingChecks() {
    true
  }

  @Override
  boolean redirectHasBody() {
    true
  }

  @Override
  boolean testExceptionBody() {
    false
  }

  @Override
  boolean hasExtraErrorInformation() {
    true
  }

  @Override
  Map<String, Serializable> expectedExtraErrorInformation(ServerEndpoint endpoint) {
    if (endpoint.throwsException) {
      ["error.message": "${endpoint.body}",
        "error.type": { it == Exception.name },
        "error.stack": String]
    } else {
      Collections.emptyMap()
    }
  }

  @Override
  boolean testRequestBody() {
    false
  }

  @Override
  boolean testRequestBodyISVariant() {
    false
  }

  @Override
  boolean testBlocking() {
    false
  }

  @Override
  boolean testBlockingOnResponse() {
    false
  }

  @Override
  boolean testBadUrl() {
    false
  }
}
