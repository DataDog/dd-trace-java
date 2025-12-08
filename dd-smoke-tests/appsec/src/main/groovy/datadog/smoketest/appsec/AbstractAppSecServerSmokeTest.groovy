package datadog.smoketest.appsec

import datadog.smoketest.AbstractServerSmokeTest
import datadog.trace.test.agent.decoder.DecodedSpan
import datadog.trace.test.agent.decoder.DecodedTrace
import datadog.trace.test.agent.decoder.Decoder
import groovy.json.JsonGenerator
import groovy.json.JsonSlurper
import org.apache.commons.io.IOUtils
import spock.lang.Shared

import java.nio.charset.StandardCharsets
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.jar.JarFile

abstract class AbstractAppSecServerSmokeTest extends AbstractServerSmokeTest {

  static class RootSpan {
    DecodedSpan span

    Map<String, String> getMeta() {
      span.meta
    }

    Map<String, Number> getMetrics() {
      span.metrics
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
    // TODO: Remove once this is the default value
    "-Ddd.api-security.enabled=true",
    "-Ddd.appsec.waf.timeout=300000",
    "-DPOWERWAF_EXIT_ON_LEAK=true",
    // disable AppSec rate limit
    "-Ddd.appsec.trace.rate.limit=-1",
    // disable http client sampling
    "-Ddd.api-security.downstream.request.body.analysis.sample_rate=1"
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

  /**
   * This method fetches default ruleset included in the agent and merges the selected rules, then it points
   * the {@code dd.appsec.rules} variable to the new file
   */
  void mergeRules(final String path, final List<Map<String, Object>> customRules) {
    mergeRules(path, customRules, null)
  }

  void mergeRules(final String path, final List<Map<String, Object>> customRules, final List< Map<String, Object>> customActions ) {
    // Prepare a file with the new rules
    final jarFile = new JarFile(shadowJarPath)
    final zipEntry = jarFile.getEntry("appsec/default_config.json")
    final content = IOUtils.toString(jarFile.getInputStream(zipEntry), StandardCharsets.UTF_8)
    final json = new JsonSlurper().parseText(content) as Map<String, Object>

    if (customActions != null) {
      def actions = json.actions as List<Map<String, Object>>
      // remove already existing rules for merge
      List<Object> customActionNames = customActions.collect {
        it.id
      }
      if( actions != null) {
        actions.removeIf {
          it.id in customActionNames
        }
      }else {
        actions = []
        json.actions = actions
      }
      actions.addAll(customActions)
    }


    final rules = json.rules as List<Map<String, Object>>

    // remove already existing rules for merge
    List<Object> customRulesNames = customRules.collect {
      it.id
    }
    rules.removeIf {
      it.id in customRulesNames
    }

    rules.addAll(customRules)
    final gen = new JsonGenerator.Options().build()
    IOUtils.write(gen.toJson(json), new FileOutputStream(path, false), StandardCharsets.UTF_8)

    // Add a new property pointing to the new ruleset
    defaultAppSecProperties += "-Ddd.appsec.rules=${path}" as String
  }
}
