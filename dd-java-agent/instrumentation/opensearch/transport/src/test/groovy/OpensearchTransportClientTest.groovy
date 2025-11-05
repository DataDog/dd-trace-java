import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.test.util.Flaky
import org.opensearch.action.admin.cluster.health.ClusterHealthRequest
import org.opensearch.client.transport.TransportClient
import org.opensearch.common.io.FileSystemUtils
import org.opensearch.common.settings.Settings
import org.opensearch.common.transport.TransportAddress
import org.opensearch.index.IndexNotFoundException
import org.opensearch.node.InternalSettingsPreparer
import org.opensearch.node.Node
import org.opensearch.transport.Netty4Plugin
import org.opensearch.transport.TransportService
import org.opensearch.transport.client.PreBuiltTransportClient
import spock.lang.Shared

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static org.opensearch.cluster.ClusterName.CLUSTER_NAME_SETTING

@Flaky
class OpensearchTransportClientTest extends InstrumentationSpecification {
  public static final long TIMEOUT = 10000 // 10 seconds

  @Shared
  TransportAddress tcpPublishAddress
  @Shared
  Node testNode
  @Shared
  File aosWorkingDir
  @Shared
  String clusterName = UUID.randomUUID().toString()

  @Shared
  TransportClient client

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
      .put(CLUSTER_NAME_SETTING.getKey(), clusterName)
      .put("node.name", "test-node")
      .put("transport.type", "netty4")
      .build()
    testNode = new Node(InternalSettingsPreparer.prepareEnvironment(
      settings, [:], null, null), [Netty4Plugin], false) {}
    testNode.start()
    tcpPublishAddress = testNode.injector().getInstance(TransportService).boundAddress().publishAddress()

    client = new PreBuiltTransportClient(
      Settings.builder()
      // Since we use listeners to close spans this should make our span closing deterministic which is good for tests
      .put("thread_pool.listener.size", 1)
      .put(CLUSTER_NAME_SETTING.getKey(), clusterName)
      .build()
      )
    client.addTransportAddress(tcpPublishAddress)
    runUnderTrace("setup") {
      // this may potentially create multiple requests and therefore multiple spans, so we wrap this call
      // into a top level trace to get exactly one trace in the result.
      client.admin().cluster().prepareHealth().setWaitForYellowStatus().execute().actionGet(TIMEOUT)
    }
    TEST_WRITER.waitForTraces(1)
  }

  def cleanupSpec() {
    testNode?.close()
    if (aosWorkingDir != null) {
      FileSystemUtils.deleteSubDirectories(aosWorkingDir.toPath())
      aosWorkingDir.delete()
    }
  }

  def "test opensearch status"() {
    setup:
    def result = client.admin().cluster().health(new ClusterHealthRequest())

    def status = result.get().status

    expect:
    status.name() == "GREEN"
    assertTraces(2) {
      trace(1) {
        span {
          serviceName "opensearch"
          resourceName "NodesStatsAction"
          operationName "opensearch.query"
          spanType DDSpanTypes.OPENSEARCH
          topLevel true
          tags {
            "$Tags.COMPONENT" "opensearch-java"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "opensearch"
            "opensearch.action" "NodesStatsAction"
            "opensearch.request" "NodesStatsRequest"
            "opensearch.node.cluster.name" clusterName
            defaultTags()
          }
        }
      }
      trace(1) {
        span {
          serviceName "opensearch"
          resourceName "IndicesStatsAction"
          operationName "opensearch.query"
          spanType DDSpanTypes.OPENSEARCH
          topLevel true
          tags {
            "$Tags.COMPONENT" "opensearch-java"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "opensearch"
            "opensearch.action" "IndicesStatsAction"
            "opensearch.request" "IndicesStatsRequest"
            "opensearch.shard.broadcast.failed" 0
            "opensearch.shard.broadcast.successful" 0
            "opensearch.shard.broadcast.total" 0
            defaultTags()
          }
        }
      }
    }
  }

  def "test opensearch error"() {
    when:
    client.prepareGet(indexName, indexType, id).get()

    then:
    thrown IndexNotFoundException

    and:
    assertTraces(2) {
      trace(1) {
        span {
          serviceName "opensearch"
          resourceName "NodesStatsAction"
          operationName "opensearch.query"
          spanType DDSpanTypes.OPENSEARCH
          topLevel true
          tags {
            "$Tags.COMPONENT" "opensearch-java"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "opensearch"
            "opensearch.action" "NodesStatsAction"
            "opensearch.request" "NodesStatsRequest"
            "opensearch.node.cluster.name" clusterName
            defaultTags()
          }
        }
      }
      trace(1) {
        span {
          serviceName "opensearch"
          resourceName "IndicesStatsAction"
          operationName "opensearch.query"
          spanType DDSpanTypes.OPENSEARCH
          topLevel true
          tags {
            "$Tags.COMPONENT" "opensearch-java"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "opensearch"
            "opensearch.action" "IndicesStatsAction"
            "opensearch.request" "IndicesStatsRequest"
            "opensearch.shard.broadcast.failed" 0
            "opensearch.shard.broadcast.successful" 0
            "opensearch.shard.broadcast.total" 0
            defaultTags()
          }
        }
      }
    }

    where:
    indexName = "invalid-index"
    indexType = null
    id = "1"
  }

  def "test opensearch get"() {
    setup:
    assert TEST_WRITER == []
    def indexResult = client.admin().indices().prepareCreate(indexName).get()
    TEST_WRITER.waitForTraces(1)

    expect:
    indexResult.index() == indexName
    TEST_WRITER.size() == 1

    when:
    def emptyResult = client.prepareGet(indexName, indexType, id).get()

    then:
    !emptyResult.isExists()
    emptyResult.id == id
    emptyResult.index == indexName

    when:
    def createResult = client.prepareIndex(indexName, indexType, id).setSource([:]).get()

    then:
    createResult.id == id
    createResult.index == indexName
    createResult.status().status == 201

    when:
    def result = client.prepareGet(indexName, indexType, id).get()

    then:
    result.isExists()
    result.id == id
    result.index == indexName

    and:
    assertTraces(5) {
      trace(1) {
        span {
          serviceName "opensearch"
          resourceName "NodesStatsAction"
          operationName "opensearch.query"
          spanType DDSpanTypes.OPENSEARCH
          topLevel true
          tags {
            "$Tags.COMPONENT" "opensearch-java"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "opensearch"
            "opensearch.action" "NodesStatsAction"
            "opensearch.request" "NodesStatsRequest"
            "opensearch.node.cluster.name" clusterName
            defaultTags()
          }
        }
      }
      trace(1) {
        span {
          serviceName "opensearch"
          resourceName "IndicesStatsAction"
          operationName "opensearch.query"
          spanType DDSpanTypes.OPENSEARCH
          topLevel true
          tags {
            "$Tags.COMPONENT" "opensearch-java"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "opensearch"
            "opensearch.action" "IndicesStatsAction"
            "opensearch.request" "IndicesStatsRequest"
            "opensearch.shard.broadcast.failed" 0
            "opensearch.shard.broadcast.successful" 1
            "opensearch.shard.broadcast.total" 2
            defaultTags()
          }
        }
      }
      trace(1) {
        span {
          serviceName "opensearch"
          resourceName "PutMappingAction"
          operationName "opensearch.query"
          spanType DDSpanTypes.OPENSEARCH
          topLevel true
          tags {
            "$Tags.COMPONENT" "opensearch-java"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "opensearch"
            "opensearch.action" "PutMappingAction"
            "opensearch.request" "PutMappingRequest"
            defaultTags()
          }
        }
      }
      trace(1) {
        span {
          serviceName "opensearch"
          resourceName "NodesStatsAction"
          operationName "opensearch.query"
          spanType DDSpanTypes.OPENSEARCH
          topLevel true
          tags {
            "$Tags.COMPONENT" "opensearch-java"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "opensearch"
            "opensearch.action" "NodesStatsAction"
            "opensearch.request" "NodesStatsRequest"
            "opensearch.node.cluster.name" clusterName
            defaultTags()
          }
        }
      }
      trace(1) {
        span {
          serviceName "opensearch"
          resourceName "IndicesStatsAction"
          operationName "opensearch.query"
          spanType DDSpanTypes.OPENSEARCH
          topLevel true
          tags {
            "$Tags.COMPONENT" "opensearch-java"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "opensearch"
            "opensearch.action" "IndicesStatsAction"
            "opensearch.request" "IndicesStatsRequest"
            "opensearch.shard.broadcast.failed" 0
            "opensearch.shard.broadcast.successful" 1
            "opensearch.shard.broadcast.total" 2
            defaultTags()
          }
        }
      }
    }

    cleanup:
    client.admin().indices().prepareDelete(indexName).get()

    where:
    indexName = "test-index"
    indexType = null
    id = "1"
  }
}
