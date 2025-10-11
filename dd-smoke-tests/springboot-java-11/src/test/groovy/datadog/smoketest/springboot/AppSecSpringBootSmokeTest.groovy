package datadog.smoketest.springboot

import datadog.smoketest.AbstractServerSmokeTest
import datadog.trace.test.agent.decoder.DecodedSpan
import datadog.trace.test.agent.decoder.DecodedTrace
import datadog.trace.test.agent.decoder.Decoder
import groovy.json.JsonGenerator
import groovy.json.JsonSlurper
import okhttp3.FormBody
import okhttp3.Request
import org.apache.commons.io.IOUtils
import spock.lang.Shared

import java.nio.charset.StandardCharsets
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.jar.JarFile

class AppSecSpringBootSmokeTest extends AbstractServerSmokeTest  {

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
    "-Ddd.appsec.enabled=true",
    "-Ddd.profiling.enabled=false",
    "-Ddd.appsec.trace.rate.limit=-1",
    '-Ddd.remote_config.enabled=false',
    '-Ddd.trace.debug=true'
  ]

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

  /**
   * This method fetches default ruleset included in the agent and merges the selected rules, then it points
   * the {@code dd.appsec.rules} variable to the new file
   */
  void mergeRules(final String path, final List<Map<String, Object>> customRules) {
    // Prepare a file with the new rules
    final jarFile = new JarFile(shadowJarPath)
    final zipEntry = jarFile.getEntry("appsec/default_config.json")
    final content = IOUtils.toString(jarFile.getInputStream(zipEntry), StandardCharsets.UTF_8)
    final json = new JsonSlurper().parseText(content) as Map<String, Object>
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

  @Shared
  String buildDir = new File(System.getProperty("datadog.smoketest.builddir")).absolutePath
  @Shared
  String customRulesPath = "${buildDir}/appsec_custom_rules.json"

  def prepareCustomRules() {
    // Prepare ruleset with additional test rules
    mergeRules(
    customRulesPath,
    [
      [
        id          : '__test_ssrf_block',
        name        : 'Server-side request forgery exploit',
        enable      : 'true',
        tags        : [
          type      : 'ssrf',
          category  : 'vulnerability_trigger',
          cwe       : '918',
          capec     : '1000/225/115/664',
          confidence: '0',
          module    : 'rasp'
        ],
        conditions  : [
          [
            parameters: [
              resource: [[address: 'server.io.net.url']],
              params  : [[address: 'server.request.body']],
            ],
            operator  : "ssrf_detector",
          ],
        ],
        transformers: [],
        on_match    : ['block']
      ]
    ])
  }

  @Override
  ProcessBuilder createProcessBuilder() {
    // We run this here to ensure it runs before starting the process. Child setupSpec runs after parent setupSpec,
    // so it is not a valid location.

    prepareCustomRules()

    String springBootShadowJar = System.getProperty('datadog.smoketest.springboot.shadowJar.path')

    List<String> command = new ArrayList<>()
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.addAll(defaultAppSecProperties)
    command.addAll((String[]) ["-jar", springBootShadowJar, "--server.port=${httpPort}"])
    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
    // Spring will print all environment variables to the log, which may pollute it and affect log assertions.
    processBuilder.environment().clear()
    return processBuilder
  }



  void 'ssrf is present (java-net)'() {
    setup:
    final url = "http://localhost:${httpPort}/ssrf/java-net"
    final body = new FormBody.Builder()
    .add("url" , "169.254.169.254")
    .add("async", async)
    .add("promise", promise ).build()
    final request = new Request.Builder().url(url).post(body).build()

    when:
    def response = client.newCall(request).execute()

    then:
    response.code() == 403
    response.body().string().contains('You\'ve been blocked')

    when:
    waitForTraceCount(1)

    then:
    def rootSpans = this.rootSpans.toList()
    rootSpans.size() == 1
    def rootSpan = rootSpans[0]
    assert rootSpan.meta.get('appsec.blocked') == 'true', 'appsec.blocked is not set'
    assert rootSpan.meta.get('_dd.appsec.json') != null, '_dd.appsec.json is not set'
    def trigger = null
    for (t in rootSpan.triggers) {
      if (t['rule']['id'] == '__test_ssrf_block') {
        trigger = t
        break
      }
    }
    assert trigger != null, 'test trigger not found'

    where:
    async   | promise
    "false" | "false"
    "true"  | "false"
    "true"  | "true"
  }

  @Override
  def logLevel() {
    return "debug"
  }
}
