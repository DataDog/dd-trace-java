package datadog.smoketest.appsec

import datadog.smoketest.AbstractServerSmokeTest
import datadog.trace.agent.test.server.http.TestHttpServer
import groovy.json.JsonSlurper
import spock.lang.AutoCleanup
import spock.lang.Shared

import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer

abstract class AbstractAppSecServerSmokeTest extends AbstractServerSmokeTest {

  @Shared
  protected BlockingQueue<Map<String, Object>> appSecEvents = new LinkedBlockingQueue<>()

  @AutoCleanup
  @Shared
  protected TestHttpServer appSecServer = httpServer {
    handlers {
      prefix("/appsec/proxy/api/v2/appsecevts") {
        JsonSlurper jsonParser = new JsonSlurper()
        Map<String, Object> attack = jsonParser.parse(request.body) as Map

        if (attack.containsKey("events")) {
          appSecEvents.addAll(attack.get("events"))
        }

        response.status(200).send("hello")
      }
    }
  }

  @Shared
  protected String[] defaultAppSecProperties = [
    "-Ddd.appsec.enabled=true",
    "-Ddd.appsec.report.timeout=0",
    // immediately commit event
    "-Ddd.profiling.enabled=false",
    "-Ddd.trace.agent.port=${appSecServer.address.port}"
  ]

  def setup() {
    appSecEvents.clear()
  }

  def setupSpec() {
    startServer()
  }

  def cleanupSpec() {
    stopServer()
  }

  def startServer() {
    appSecServer.start()
  }

  def stopServer() {
    // do nothing; 'server' is autocleanup
  }
}
