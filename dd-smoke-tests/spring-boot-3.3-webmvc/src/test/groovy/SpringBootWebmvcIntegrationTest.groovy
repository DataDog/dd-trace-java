import okhttp3.Request

class SpringBootWebmvcIntegrationTest extends AbstractSpringBootWebmvcIntegrationTest {

  @Override
  protected Set<String> expectedTraces() {
    return [
      "\\[servlet\\.request:GET /fruits\\[spring\\.handler:FruitController\\.listFruits\\[repository\\.operation:FruitRepository\\.findAll\\[h2\\.query:.*",
      "\\[servlet\\.request:GET /fruits/\\{name\\}\\[spring\\.handler:FruitController\\.findOneFruit\\[repository\\.operation:FruitRepository\\.findByName\\[h2\\.query:.*"
    ]
  }

  def "find all fruits"() {
    setup:
    String url = "http://localhost:${httpPort}/fruits"

    when:
    def response = client.newCall(new Request.Builder().url(url).get().build()).execute()

    then:
    def responseBodyStr = response.body().string()
    responseBodyStr != null
    ["banana", "apple", "orange"].each { responseBodyStr.contains(it) }
    waitForTraceCount(1)
  }

  def "find a banana"() {
    setup:
    String url = "http://localhost:${httpPort}/fruits/banana"

    when:
    def response = client.newCall(new Request.Builder().url(url).get().build()).execute()

    then:
    def responseBodyStr = response.body().string()
    responseBodyStr != null
    ["apple", "orange"].each { !responseBodyStr.contains(it) }
    responseBodyStr.contains("banana")
    waitForTraceCount(1)
  }
}
