package datadog.smoketest.appsec

import datadog.smoketest.AbstractServerSmokeTest
import datadog.trace.test.agent.decoder.DecodedTrace
import datadog.trace.test.agent.decoder.Decoder
import groovy.json.JsonSlurper
import spock.lang.Shared

import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue

abstract class AbstractAppSecServerSmokeTest extends AbstractServerSmokeTest {

  @Shared
  protected BlockingQueue<Map<String, Object>> appSecEvents = new LinkedBlockingQueue<>()

  @Shared
  protected String[] defaultAppSecProperties = [
    "-Ddd.appsec.enabled=true",
    "-Ddd.profiling.enabled=false",
    // decoding received traces is only available for v0.5 right now
    "-Ddd.trace.agent.v0.5.enabled=true",
  ]

  @Override
  Closure decodedTracesCallback() {
    return { List<DecodedTrace> tr ->
      JsonSlurper jsonParser = new JsonSlurper()
      tr.forEach {
        // The appsec report json is on the root span
        def root = Decoder.sortByStart(it.spans).head()
        def appsecJSON = root.meta.get("_dd.appsec.json")
        if (appsecJSON) {
          Map<String, Object> attack = jsonParser.parse(appsecJSON.toCharArray()) as Map
          Map<String, Object> triggers = attack.get("triggers") as Map
          if (triggers) {
            appSecEvents.addAll(triggers)
          }
        }
      }
    }
  }

  def setup() {
    appSecEvents.clear()
  }

  def cleanup() {
    appSecEvents.clear()
  }
}
