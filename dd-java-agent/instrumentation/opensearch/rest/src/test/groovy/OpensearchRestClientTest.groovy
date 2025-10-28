import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import groovy.json.JsonSlurper
import org.apache.http.HttpHost
import org.apache.http.client.config.RequestConfig
import org.apache.http.util.EntityUtils
import org.opensearch.client.Request
import org.opensearch.client.Response
import org.opensearch.client.RestClient
import org.opensearch.client.RestClientBuilder
import org.opensearch.common.io.FileSystemUtils
import org.opensearch.common.settings.Settings
import org.opensearch.common.transport.TransportAddress
import org.opensearch.http.HttpServerTransport
import org.opensearch.node.InternalSettingsPreparer
import org.opensearch.node.Node
import org.opensearch.transport.Netty4Plugin
import spock.lang.Shared

class OpensearchRestClientTest extends InstrumentationSpecification {
  @Shared
  TransportAddress httpTransportAddress
  @Shared
  Node testNode
  @Shared
  File aosWorkingDir
  @Shared
  String clusterName = UUID.randomUUID().toString()

  @Shared
  RestClient client

  @Override
  boolean useStrictTraceWrites() {
    //FIXME IDM
    false
  }

  def setupSpec() {

    aosWorkingDir = File.createTempDir("test-aos-working-dir-", "")
    aosWorkingDir.deleteOnExit()
    println "AOS work dir: $aosWorkingDir"

    def settings = Settings.builder()
      .put("path.home", aosWorkingDir.path)
      .put("cluster.name", clusterName)
      .put("node.name", "test-node")
      .put("transport.type", "netty4")
      .build()
    testNode = new Node(InternalSettingsPreparer.prepareEnvironment(
      settings, [:], null, null), [Netty4Plugin], false) {}
    testNode.start()
    httpTransportAddress = testNode.injector().getInstance(HttpServerTransport).boundAddress().publishAddress()

    client = RestClient.builder(new HttpHost(httpTransportAddress.address, httpTransportAddress.port))
      .setRequestConfigCallback(new RestClientBuilder.RequestConfigCallback() {
        @Override
        RequestConfig.Builder customizeRequestConfig(RequestConfig.Builder builder) {
          return builder.setConnectTimeout(Integer.MAX_VALUE).setSocketTimeout(Integer.MAX_VALUE)
        }
      })
      .build()
  }

  def cleanupSpec() {
    testNode?.close()
    if (aosWorkingDir != null) {
      FileSystemUtils.deleteSubDirectories(aosWorkingDir.toPath())
      aosWorkingDir.delete()
    }
  }

  def "test opensearch status #nr"() {
    setup:
    Request request = new Request("GET", "_cluster/health")
    Response response = client.performRequest(request)

    Map result = new JsonSlurper().parseText(EntityUtils.toString(response.entity))

    expect:
    result.status == "green"

    assertTraces(1) {
      sortSpansByStart()
      trace(2) {
        span {
          serviceName "opensearch"
          resourceName "GET _cluster/health"
          operationName "opensearch.rest.query"
          spanType DDSpanTypes.OPENSEARCH
          parent()
          tags {
            "$Tags.COMPONENT" "opensearch-java"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.PEER_HOSTNAME" httpTransportAddress.address
            "$Tags.PEER_PORT" httpTransportAddress.port
            "$Tags.HTTP_URL" "_cluster/health"
            "$Tags.HTTP_METHOD" "GET"
            "$Tags.DB_TYPE" "opensearch"
            defaultTags()
          }
        }
        span {
          serviceName "opensearch"
          resourceName "GET /_cluster/health"
          operationName "http.request"
          spanType DDSpanTypes.HTTP_CLIENT
          childOf span(0)
          tags {
            "$Tags.COMPONENT" "apache-httpasyncclient"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.HTTP_URL" "http://${httpTransportAddress.address}:${httpTransportAddress.port}/_cluster/health"
            "$Tags.HTTP_METHOD" "GET"
            "$Tags.HTTP_STATUS" 200
            "$Tags.PEER_HOSTNAME" httpTransportAddress.address
            "$Tags.PEER_PORT" httpTransportAddress.port
            defaultTags()
          }
        }
      }
    }

    where:
    nr << (1..101)
  }
}
