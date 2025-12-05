package datadog.smoketest.appsec.springbootgraphql

import com.fasterxml.jackson.databind.ObjectMapper
import datadog.smoketest.appsec.AbstractAppSecServerSmokeTest
import groovy.json.JsonGenerator
import groovy.json.JsonSlurper
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody
import org.apache.commons.io.IOUtils
import spock.lang.Shared

import java.nio.charset.StandardCharsets
import java.util.jar.JarFile

class SpringBootGraphqlSmokeTest extends AbstractAppSecServerSmokeTest {

  @Shared
  String buildDir = new File(System.getProperty("datadog.smoketest.builddir")).absolutePath
  @Shared
  String customRulesPath = "${buildDir}/appsec_custom_rules.json"

  def prepareCustomRules() {
    // Prepare ruleset with additional test rules
    final jarFile = new JarFile(shadowJarPath)
    final zipEntry = jarFile.getEntry("appsec/default_config.json")
    final content = IOUtils.toString(jarFile.getInputStream(zipEntry), StandardCharsets.UTF_8)
    final json = new JsonSlurper().parseText(content) as Map<String, Object>
    final rules = json.rules as List<Map<String, Object>>
    rules.add([
      id: '__test_request_body',
      name: 'test rule to detect on request body',
      tags: [
        type: 'test',
        category: 'test',
        confidence: '1',
      ],
      conditions: [
        [
          parameters: [
            inputs: [ [ address: 'server.request.body' ] ],
            regex: 'bodyfindme',
          ],
          operator: 'match_regex',
        ]
      ],
      transformers: []
    ])
    rules.add([
      id: '__test_graphql_resolver',
      name: 'test rule to detect graphql resolver',
      tags: [
        type: 'test',
        category: 'test',
        confidence: '1',
      ],
      conditions: [
        [
          parameters: [
            inputs: [ [ address: 'graphql.server.all_resolvers' ] ],
            regex: 'graphqlfindme',
          ],
          operator: 'match_regex',
        ]
      ],
      transformers: []
    ])
    final gen = new JsonGenerator.Options().build()
    IOUtils.write(gen.toJson(json), new FileOutputStream(customRulesPath, false), StandardCharsets.UTF_8)
  }

  @Override
  ProcessBuilder createProcessBuilder() {
    // We run this here to ensure it runs before starting the process. Child setupSpec runs after parent setupSpec,
    // so it is not a valid location.
    prepareCustomRules()

    String springBootShadowJar = System.getProperty("datadog.smoketest.appsec.springboot-graphql.shadowJar.path")
    assert springBootShadowJar != null

    List<String> command = [
      javaPath(),
      *defaultJavaProperties,
      *defaultAppSecProperties,
      "-Ddd.appsec.rules=${customRulesPath}",
      "-jar",
      springBootShadowJar,
      "--server.port=${httpPort}"
    ].collect { it as String }

    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
  }

  class Query {
    String query

    Query(String query) {
      this.query = query
    }
  }

  def 'test detect #description'() {
    setup:

    def garbageBytes = "Q" * garbageLength

    def query = """query {
                # This is a garbage ${garbageBytes}
                
                bookById(id: "bodyfindme-graphqlfindme-book-1") {
                    id
                    name
                    pageCount
                    author {
                        id
                        firstName
                        lastName
                    }
                }
            }"""

    Query queryObj = new Query(query)
    ObjectMapper mapper = new ObjectMapper()
    String queryJson = mapper.writeValueAsString(queryObj)

    String url = "http://localhost:${httpPort}/graphql"
    def request = new Request.Builder()
      .url(url)
      .post(RequestBody.create(MediaType.parse("application/json"), queryJson))
      .addHeader('content-type', 'application/json')
      .build()

    when:
    def response = client.newCall(request).execute()

    then:
    response.code() == 200
    def responseBodyStr = response.body().string()
    responseBodyStr != null
    responseBodyStr.contains('{"data":{"bookById":null}}')
    response.body().contentType().toString().contains("application/json")

    and:
    waitForTraceCount(1) == 1
    rootSpans.size() == 1
    def graphqlRootSpan = rootSpans.find { it.triggers }

    graphqlRootSpan.triggers[0]['rule']['tags']['type'] == type
    graphqlRootSpan.triggers[0]['rule_matches'][0]['parameters']['address'][0] == address

    where:
    description            | type   | address                        | garbageLength

    // normal graphql detected as body attack
    'request body attack'  | 'test' | 'server.request.body'          | 0

    // 4KB allow to bypass WAF but detected as graphql attack
    'graphql attack'       | 'test' | 'graphql.server.all_resolvers' | 4096
  }
}
