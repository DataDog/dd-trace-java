import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.test.util.Flaky
import groovy.json.JsonSlurper
import org.apache.http.HttpHost
import org.apache.http.client.config.RequestConfig
import org.apache.http.util.EntityUtils
import org.elasticsearch.client.Response
import org.elasticsearch.client.RestClient
import org.elasticsearch.client.RestClientBuilder
import org.elasticsearch.common.io.FileSystemUtils
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.common.transport.TransportAddress
import org.elasticsearch.env.Environment
import org.elasticsearch.http.HttpServerTransport
import org.elasticsearch.node.InternalSettingsPreparer
import org.elasticsearch.node.Node
import org.elasticsearch.plugins.Plugin
import org.elasticsearch.transport.Netty4Plugin
import spock.lang.Shared

@Flaky
class Elasticsearch6RestClientTest extends InstrumentationSpecification {

  @Shared
  TransportAddress httpTransportAddress
  @Shared
  Node testNode
  @Shared
  File esWorkingDir

  @Shared
  RestClient client
  @Shared
  String clusterName = UUID.randomUUID().toString()

  def setupSpec() {

    esWorkingDir = File.createTempDir("test-es-working-dir-", "")
    esWorkingDir.deleteOnExit()
    println "ES work dir: $esWorkingDir"

    def settings = Settings.builder()
      .put("path.home", esWorkingDir.path)
      .put("cluster.name", clusterName)
      .build()
    testNode = new TestNode(InternalSettingsPreparer.prepareEnvironment(settings, null), [Netty4Plugin])
    testNode.start()
    httpTransportAddress = testNode.injector().getInstance(HttpServerTransport).boundAddress().publishAddress()

    client = RestClient.builder(new HttpHost(httpTransportAddress.address, httpTransportAddress.port))
      .setMaxRetryTimeoutMillis(Integer.MAX_VALUE)
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
    if (esWorkingDir != null) {
      FileSystemUtils.deleteSubDirectories(esWorkingDir.toPath())
      esWorkingDir.delete()
    }
  }

  def "test elasticsearch status"() {
    setup:
    injectSysConfig("httpasyncclient4.legacy.tracing.enabled",  "true")

    Response response = client.performRequest("GET", "_cluster/health")

    Map result = new JsonSlurper().parseText(EntityUtils.toString(response.entity))

    expect:
    result.status == "green"

    assertTraces(1) {
      trace(2) {
        span {
          serviceName "elasticsearch"
          resourceName "GET _cluster/health"
          operationName "elasticsearch.rest.query"
          spanType DDSpanTypes.ELASTICSEARCH
          parent()
          tags {
            "$Tags.COMPONENT" "elasticsearch-java"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.PEER_HOSTNAME" httpTransportAddress.address
            "$Tags.PEER_PORT" httpTransportAddress.port
            "$Tags.HTTP_URL" "_cluster/health"
            "$Tags.HTTP_METHOD" "GET"
            "$Tags.DB_TYPE" "elasticsearch"
            defaultTags()
          }
        }
        span {
          serviceName "elasticsearch"
          resourceName "GET _cluster/health"
          operationName "http.request"
          spanType DDSpanTypes.HTTP_CLIENT
          childOf span(0)
          tags {
            "$Tags.COMPONENT" "apache-httpasyncclient"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.HTTP_URL" "_cluster/health"
            "$Tags.HTTP_METHOD" "GET"
            "$Tags.HTTP_STATUS" 200
            defaultTagsNoPeerService()
          }
        }
      }
    }
  }

  static class TestNode extends Node {
    TestNode(Environment environment, Collection<Class<? extends Plugin>> classpathPlugins) {
      super(environment, classpathPlugins, false)
    }

    @Override
    protected void registerDerivedNodeNameWithLogger(String nodeName) {
    }
  }
}
