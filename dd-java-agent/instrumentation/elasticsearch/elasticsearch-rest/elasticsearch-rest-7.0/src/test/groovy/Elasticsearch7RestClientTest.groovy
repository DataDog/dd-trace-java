import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import groovy.json.JsonSlurper
import org.apache.http.HttpHost
import org.apache.http.client.config.RequestConfig
import org.apache.http.util.EntityUtils
import org.elasticsearch.client.Request
import org.elasticsearch.client.Response
import org.elasticsearch.client.ResponseListener
import org.elasticsearch.client.RestClient
import org.elasticsearch.client.RestClientBuilder
import org.elasticsearch.common.io.FileSystemUtils
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.common.transport.TransportAddress
import org.elasticsearch.http.HttpServerTransport
import org.elasticsearch.node.InternalSettingsPreparer
import org.elasticsearch.node.Node
import org.elasticsearch.transport.Netty4Plugin
import spock.lang.Shared

import java.util.concurrent.CountDownLatch

import static java.util.concurrent.TimeUnit.SECONDS

class Elasticsearch7RestClientTest extends InstrumentationSpecification {
  @Shared
  TransportAddress httpTransportAddress
  @Shared
  Node testNode
  @Shared
  File esWorkingDir
  @Shared
  String clusterName = UUID.randomUUID().toString()

  @Shared
  RestClient client

  def setupSpec() {
    esWorkingDir = File.createTempDir("test-es-working-dir-", "")
    esWorkingDir.deleteOnExit()
    println "ES work dir: $esWorkingDir"

    def settings = Settings.builder()
      .put("path.home", esWorkingDir.path)
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
    if (esWorkingDir != null) {
      FileSystemUtils.deleteSubDirectories(esWorkingDir.toPath())
      esWorkingDir.delete()
    }
  }

  def "test elasticsearch sync status #nr"() {
    setup:
    Request request = new Request("GET", "_cluster/health")
    Response response = client.performRequest(request)

    Map result = new JsonSlurper().parseText(EntityUtils.toString(response.entity))

    expect:
    result.status == "green"
    assertClientTraces()

    where:
    nr << (1..101)
  }

  def "test elasticsearch async status request #nr"() {
    setup:
    Request request = new Request("GET", "_cluster/health")

    def listener = new BlockingResponseListener()
    client.performRequestAsync(request, listener)
    def done = listener.waitFor()

    expect:
    done
    listener.response != null
    listener.exception == null

    when:
    Map result = new JsonSlurper().parseText(EntityUtils.toString(listener.response.entity))

    then:
    result.status == "green"
    assertClientTraces()

    where:
    nr << (1..101)
  }

  private assertClientTraces() {
    assertTraces(1) {
      sortSpansByStart()
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
          resourceName "GET /_cluster/health"
          operationName "http.request"
          spanType DDSpanTypes.HTTP_CLIENT
          childOf span(0)
          tags {
            "$Tags.COMPONENT" "apache-httpasyncclient"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.PEER_HOSTNAME" httpTransportAddress.address
            "$Tags.PEER_PORT" httpTransportAddress.port
            "$Tags.HTTP_URL" "http://${httpTransportAddress.address}:${httpTransportAddress.port}/_cluster/health"
            "$Tags.HTTP_METHOD" "GET"
            "$Tags.HTTP_STATUS" 200
            defaultTags()
          }
        }
      }
    }
    return true
  }

  static class BlockingResponseListener implements ResponseListener {
    private final CountDownLatch latch
    Response response
    Exception exception

    BlockingResponseListener() {
      this.latch = new CountDownLatch(1)
    }

    @Override
    void onSuccess(Response response) {
      this.response = response
      this.latch.countDown()
    }

    @Override
    void onFailure(Exception exception) {
      this.exception = exception
      this.latch.countDown()
    }

    boolean waitFor() {
      return this.latch.await(2, SECONDS)
    }
  }
}
