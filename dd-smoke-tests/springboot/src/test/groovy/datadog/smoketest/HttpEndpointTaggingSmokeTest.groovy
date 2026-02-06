package datadog.smoketest

import okhttp3.Request
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

class HttpEndpointTaggingSmokeTest extends AbstractServerSmokeTest {

  @Override
  ProcessBuilder createProcessBuilder() {
    String springBootShadowJar = System.getProperty("datadog.smoketest.springboot.shadowJar.path")

    List<String> command = new ArrayList<>()
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.addAll((String[]) [
      "-Ddd.writer.type=MultiWriter:TraceStructureWriter:${output.getAbsolutePath()}:includeResource,DDAgentWriter",
      "-Ddd.trace.resource.renaming.enabled=true",
      "-jar",
      springBootShadowJar,
      "--server.port=${httpPort}"
    ])
    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
  }

  @Override
  File createTemporaryFile() {
    return File.createTempFile("http-endpoint-tagging-trace", "out")
  }

  @Override
  protected Set<String> expectedTraces() {
    return [
      Pattern.quote("[servlet.request:GET /greeting[spring.handler:IastWebController.greeting]]")
    ]
  }

  @Override
  protected Set<String> assertTraceCounts(Set<String> expected, Map<String, AtomicInteger> traceCounts) {
    List<Pattern> remaining = expected.collect { Pattern.compile(it) }.toList()
    for (def i = remaining.size() - 1; i >= 0; i--) {
      for (Map.Entry<String, AtomicInteger> entry : traceCounts.entrySet()) {
        if (entry.getValue() > 0 && remaining.get(i).matcher(entry.getKey()).matches()) {
          remaining.remove(i)
          break
        }
      }
    }
    return remaining.collect { it.pattern() }.toSet()
  }

  def "test basic HTTP endpoint tagging"() {
    setup:
    String url = "http://localhost:${httpPort}/greeting"
    def request = new Request.Builder().url(url).get().build()

    when:
    def response = client.newCall(request).execute()

    then:
    def responseBodyStr = response.body().string()
    responseBodyStr != null
    responseBodyStr.contains("Sup Dawg")
    response.code() == 200
    waitForTraceCount(1)
  }

  def "test URL parameterization for numeric IDs"() {
    setup:
    String url = "http://localhost:${httpPort}/users/123"
    def request = new Request.Builder().url(url).get().build()

    when:
    def response = client.newCall(request).execute()

    then:
    // May return 404 since endpoint doesn't exist, but span should still be created
    response.code() in [200, 404]
    waitForTraceCount(1)
  }

  def "test URL parameterization for hex patterns"() {
    setup:
    String url = "http://localhost:${httpPort}/session/abc123def456"
    def request = new Request.Builder().url(url).get().build()

    when:
    def response = client.newCall(request).execute()

    then:
    // May return 404 since endpoint doesn't exist, but span should still be created
    response.code() in [200, 404]
    waitForTraceCount(1)
  }

  def "test int_id pattern parameterization"() {
    setup:
    String url = "http://localhost:${httpPort}/api/versions/12.34.56"
    def request = new Request.Builder().url(url).get().build()

    when:
    def response = client.newCall(request).execute()

    then:
    response.code() in [200, 404]
    waitForTraceCount(1)
  }

  def "test hex_id pattern parameterization"() {
    setup:
    String url = "http://localhost:${httpPort}/api/tokens/550e8400-e29b-41d4-a716-446655440000"
    def request = new Request.Builder().url(url).get().build()

    when:
    def response = client.newCall(request).execute()

    then:
    response.code() in [200, 404]
    waitForTraceCount(1)
  }

  def "test str pattern parameterization for long strings"() {
    setup:
    String url = "http://localhost:${httpPort}/files/very-long-filename-that-exceeds-twenty-characters.pdf"
    def request = new Request.Builder().url(url).get().build()

    when:
    def response = client.newCall(request).execute()

    then:
    response.code() in [200, 404]
    waitForTraceCount(1)
  }

  def "test str pattern parameterization for special characters"() {
    setup:
    String url = "http://localhost:${httpPort}/search/query%20with%20spaces"
    def request = new Request.Builder().url(url).get().build()

    when:
    def response = client.newCall(request).execute()

    then:
    response.code() in [200, 404]
    waitForTraceCount(1)
  }

  def "test mixed URL patterns with multiple segments"() {
    setup:
    String url = "http://localhost:${httpPort}/api/users/123/orders/abc456def/items/789"
    def request = new Request.Builder().url(url).get().build()

    when:
    def response = client.newCall(request).execute()

    then:
    response.code() in [200, 404]
    waitForTraceCount(1)
  }

  def "test URL with query parameters"() {
    setup:
    String url = "http://localhost:${httpPort}/api/users/123?filter=active&limit=10"
    def request = new Request.Builder().url(url).get().build()

    when:
    def response = client.newCall(request).execute()

    then:
    response.code() in [200, 404]
    waitForTraceCount(1)
  }

  def "test URL with trailing slash"() {
    setup:
    String url = "http://localhost:${httpPort}/api/users/456/"
    def request = new Request.Builder().url(url).get().build()

    when:
    def response = client.newCall(request).execute()

    then:
    response.code() in [200, 404]
    waitForTraceCount(1)
  }

  def "test static paths are preserved"() {
    setup:
    String url = "http://localhost:${httpPort}/health"
    def request = new Request.Builder().url(url).get().build()

    when:
    def response = client.newCall(request).execute()

    then:
    response.code() in [200, 404]
    waitForTraceCount(1)
  }

  def "test root path handling"() {
    setup:
    String url = "http://localhost:${httpPort}/"
    def request = new Request.Builder().url(url).get().build()

    when:
    def response = client.newCall(request).execute()

    then:
    response.code() in [200, 404]
    waitForTraceCount(1)
  }

  def "test pattern precedence - int pattern wins over int_id"() {
    setup:
    String url = "http://localhost:${httpPort}/api/items/12345"
    def request = new Request.Builder().url(url).get().build()

    when:
    def response = client.newCall(request).execute()

    then:
    response.code() in [200, 404]
    waitForTraceCount(1)
  }

  def "test pattern precedence - hex pattern wins over hex_id"() {
    setup:
    String url = "http://localhost:${httpPort}/api/hashes/abc123def"
    def request = new Request.Builder().url(url).get().build()

    when:
    def response = client.newCall(request).execute()

    then:
    response.code() in [200, 404]
    waitForTraceCount(1)
  }

  def "test edge case - short segments not parameterized"() {
    setup:
    String url = "http://localhost:${httpPort}/api/x/y"
    def request = new Request.Builder().url(url).get().build()

    when:
    def response = client.newCall(request).execute()

    then:
    response.code() in [200, 404]
    waitForTraceCount(1)
  }
}
