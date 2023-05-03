package datadog.smoketest.appsec

import datadog.smoketest.AbstractServerSmokeTest
import datadog.trace.test.agent.decoder.DecodedSpan
import datadog.trace.test.agent.decoder.DecodedTrace
import datadog.trace.test.agent.decoder.Decoder
import groovy.json.JsonSlurper
import spock.lang.Shared

import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue

abstract class AbstractAppSecServerSmokeTest extends AbstractServerSmokeTest {

  static class RootSpan {
    DecodedSpan span

    Map<String, String> getMeta() {
      span.meta
    }

    List<Map<String, Object>> getTriggers() {
      def appsecJSON = meta.get("_dd.appsec.json")
      if (appsecJSON) {
        JsonSlurper jsonParser = new JsonSlurper()
        Map<String, Object> attack = jsonParser.parse(appsecJSON.toCharArray()) as Map
        Map<String, Object> triggers = attack.get("triggers") as Map
        if (triggers) {
          return triggers
        }
      }
      []
    }
  }

  @Shared
  protected BlockingQueue<RootSpan> rootSpans = new LinkedBlockingQueue<>()

  @Shared
  protected String[] defaultAppSecProperties = [
    "-Ddd.appsec.enabled=${System.getProperty('smoke_test.appsec.enabled') ?: 'true'}",
    "-Ddd.profiling.enabled=false",
    // decoding received traces is only available for v0.5 right now
    "-Ddd.trace.agent.v0.5.enabled=true",
    // disable AppSec rate limit
    "-Ddd.appsec.trace.rate.limit=-1"
  ] + (System.getProperty('smoke_test.appsec.enabled') == 'inactive' ?
  // enable remote config so that appsec is partially enabled (rc is now enabled by default)
  [
    '-Ddd.remote_config.url=https://127.0.0.1:54670/invalid_endpoint',
    '-Ddd.remote_config.poll_interval.seconds=3600'
  ]:
  ['-Ddd.remote_config.enabled=false']
  )

  @Override
  Closure decodedTracesCallback() {
    return { List<DecodedTrace> tr ->
      tr.forEach {
        // The appsec report json is on the root span
        def root = Decoder.sortByStart(it.spans).head()
        rootSpans << new RootSpan(span: root)
      }
    }
  }

  def setup() {
    rootSpans.clear()
  }

  def cleanup() {
    rootSpans.clear()
  }

  void forEachRootSpanTrigger(Closure closure) {
    rootSpans.each {
      assert it.meta.get('_dd.appsec.json') != null, 'No attack detected'
    }
    rootSpans.collectMany {it.triggers }.forEach closure
  }
}
