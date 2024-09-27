package datadog.trace.civisibility

import datadog.trace.agent.test.server.http.TestHttpServer
import datadog.trace.agent.test.utils.OkHttpUtils
import spock.lang.Specification

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer

class CiVisibilityServicesTest extends Specification {

  def "test get remote environment"() {
    given:
    def key = "the-key"

    TestHttpServer remoteEnvironmentServer = httpServer {
      handlers {
        prefix("/") {
          if (request.getHeader(CiVisibilityServices.DD_ENV_VARS_PROVIDER_KEY_HEADER) == key) {
            response.status(200).send(""" { "a": 1, "b": "2" } """)
          } else {
            response.status(404).send()
          }
        }
      }
    }

    expect:
    CiVisibilityServices.getRemoteEnvironment(remoteEnvironmentServer.address.toString(), key, OkHttpUtils.client()) == ["a": "1", "b": "2"]

    cleanup:
    remoteEnvironmentServer.stop()
  }
}
