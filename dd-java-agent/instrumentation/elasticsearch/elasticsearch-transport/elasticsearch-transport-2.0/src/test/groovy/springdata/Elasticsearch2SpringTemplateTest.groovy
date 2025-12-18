package springdata

import datadog.trace.agent.test.naming.VersionedNamingTestBase
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.test.util.Flaky
import org.elasticsearch.action.search.SearchResponse
import org.elasticsearch.common.io.FileSystemUtils
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.index.IndexNotFoundException
import org.elasticsearch.node.Node
import org.elasticsearch.node.NodeBuilder
import org.elasticsearch.search.aggregations.bucket.nested.InternalNested
import org.elasticsearch.search.aggregations.bucket.terms.Terms
import org.springframework.data.elasticsearch.core.ElasticsearchTemplate
import org.springframework.data.elasticsearch.core.ResultsExtractor
import org.springframework.data.elasticsearch.core.query.IndexQueryBuilder
import org.springframework.data.elasticsearch.core.query.NativeSearchQuery
import org.springframework.data.elasticsearch.core.query.NativeSearchQueryBuilder
import spock.lang.Shared

import java.util.concurrent.atomic.AtomicLong

@Flaky
abstract class Elasticsearch2SpringTemplateTest extends VersionedNamingTestBase {
  public static final long TIMEOUT = 10000 // 10 seconds

  @Shared
  Node testNode
  @Shared
  File esWorkingDir
  @Shared
  String clusterName = UUID.randomUUID().toString()

  @Shared
  ElasticsearchTemplate template

  def setupSpec() {

    esWorkingDir = File.createTempDir("test-es-working-dir-", "")
    esWorkingDir.deleteOnExit()
    println "ES work dir: $esWorkingDir"

    def settings = Settings.builder()
      .put("path.home", esWorkingDir.path)
      // Since we use listeners to close spans this should make our span closing deterministic which is good for tests
      .put("threadpool.listener.size", 1)
      .build()
    testNode = NodeBuilder.newInstance().local(true).clusterName(clusterName).settings(settings).build()
    testNode.start()

    template = new ElasticsearchTemplate(testNode.client())
  }

  def cleanupSpec() {
    testNode?.close()
    if (esWorkingDir != null) {
      FileSystemUtils.deleteSubDirectories(esWorkingDir.toPath())
      esWorkingDir.delete()
    }
  }

  def "test elasticsearch error"() {
    setup:

    when:
    template.refresh(indexName)

    then:
    thrown IndexNotFoundException

    and:
    assertTraces(1) {
      trace(1) {
        span {
          serviceName service()
          resourceName "RefreshAction"
          operationName operation()
          spanType DDSpanTypes.ELASTICSEARCH
          errored true
          tags {
            "$Tags.COMPONENT" "elasticsearch-java"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "elasticsearch"
            "elasticsearch.action" "RefreshAction"
            "elasticsearch.request" "RefreshRequest"
            "elasticsearch.request.indices" indexName
            errorTags IndexNotFoundException, "no such index"
            defaultTagsNoPeerService()
          }
        }
      }
    }

    where:
    indexName = "invalid-index"
  }

  def "test elasticsearch get"() {
    setup:

    expect:
    template.createIndex(indexName)
    TEST_WRITER.waitForTraces(1)
    template.getClient().admin().cluster().prepareHealth().setWaitForYellowStatus().execute().actionGet(TIMEOUT)
    TEST_WRITER.waitForTraces(2)

    when:
    NativeSearchQuery query = new NativeSearchQueryBuilder()
      .withIndices(indexName)
      .withTypes(indexType)
      .withIds([id])
      .build()

    then:
    template.queryForIds(query) == []

    when:
    def result = template.index(IndexQueryBuilder.newInstance()
      .withObject(new Doc())
      .withIndexName(indexName)
      .withType(indexType)
      .withId(id)
      .build())
    template.refresh(Doc)

    then:
    result == id
    template.queryForList(query, Doc) == [new Doc()]

    and:
    assertTraces(7) {
      trace(1) {
        span {
          serviceName service()
          resourceName "CreateIndexAction"
          operationName operation()
          spanType DDSpanTypes.ELASTICSEARCH
          tags {
            "$Tags.COMPONENT" "elasticsearch-java"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "elasticsearch"
            "elasticsearch.action" "CreateIndexAction"
            "elasticsearch.request" "CreateIndexRequest"
            "elasticsearch.request.indices" indexName
            defaultTagsNoPeerService()
          }
        }
      }
      trace(1) {
        span {
          serviceName service()
          resourceName "ClusterHealthAction"
          operationName operation()
          spanType DDSpanTypes.ELASTICSEARCH
          tags {
            "$Tags.COMPONENT" "elasticsearch-java"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "elasticsearch"
            "elasticsearch.action" "ClusterHealthAction"
            "elasticsearch.request" "ClusterHealthRequest"
            defaultTagsNoPeerService()
          }
        }
      }
      trace(1) {
        span {
          serviceName service()
          resourceName "SearchAction"
          operationName operation()
          spanType DDSpanTypes.ELASTICSEARCH
          tags {
            "$Tags.COMPONENT" "elasticsearch-java"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "elasticsearch"
            "elasticsearch.action" "SearchAction"
            "elasticsearch.request" "SearchRequest"
            "elasticsearch.request.indices" indexName
            "elasticsearch.request.search.types" indexType
            defaultTagsNoPeerService()
          }
        }
      }
      trace(1) {
        span {
          serviceName service()
          resourceName "IndexAction"
          operationName operation()
          spanType DDSpanTypes.ELASTICSEARCH
          tags {
            "$Tags.COMPONENT" "elasticsearch-java"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.PEER_HOSTNAME" "local"
            "$Tags.PEER_HOST_IPV4" "0.0.0.0"
            "$Tags.DB_TYPE" "elasticsearch"
            "elasticsearch.action" "IndexAction"
            "elasticsearch.request" "IndexRequest"
            "elasticsearch.request.indices" indexName
            "elasticsearch.request.write.type" indexType
            peerServiceFrom(Tags.PEER_HOSTNAME)
            defaultTags()
          }
        }
      }
      trace(1) {
        span {
          serviceName service()
          resourceName "PutMappingAction"
          operationName operation()
          spanType DDSpanTypes.ELASTICSEARCH
          tags {
            "$Tags.COMPONENT" "elasticsearch-java"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "elasticsearch"
            "elasticsearch.action" "PutMappingAction"
            "elasticsearch.request" "PutMappingRequest"
            "elasticsearch.request.indices" indexName
            defaultTagsNoPeerService()
          }
        }
      }
      trace(1) {
        span {
          serviceName service()
          resourceName "RefreshAction"
          operationName operation()
          spanType DDSpanTypes.ELASTICSEARCH
          tags {
            "$Tags.COMPONENT" "elasticsearch-java"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "elasticsearch"
            "elasticsearch.action" "RefreshAction"
            "elasticsearch.request" "RefreshRequest"
            "elasticsearch.request.indices" indexName
            "elasticsearch.shard.broadcast.failed" 0
            "elasticsearch.shard.broadcast.successful" 5
            "elasticsearch.shard.broadcast.total" 10
            defaultTagsNoPeerService()
          }
        }
      }
      trace(1) {
        span {
          serviceName service()
          resourceName "SearchAction"
          operationName operation()
          spanType DDSpanTypes.ELASTICSEARCH
          tags {
            "$Tags.COMPONENT" "elasticsearch-java"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "elasticsearch"
            "elasticsearch.action" "SearchAction"
            "elasticsearch.request" "SearchRequest"
            "elasticsearch.request.indices" indexName
            "elasticsearch.request.search.types" indexType
            defaultTagsNoPeerService()
          }
        }
      }
    }

    cleanup:
    template.deleteIndex(indexName)

    where:
    indexName = "test-index"
    indexType = "test-type"
    id = "1"
  }

  def "test results extractor"() {
    setup:

    template.createIndex(indexName)
    TEST_WRITER.waitForTraces(1)
    testNode.client().admin().cluster().prepareHealth().setWaitForYellowStatus().execute().actionGet(TIMEOUT)
    TEST_WRITER.waitForTraces(2)

    template.index(IndexQueryBuilder.newInstance()
      .withObject(new Doc(id: 1, data: "doc a"))
      .withIndexName(indexName)
      .withId("a")
      .build())
    template.index(IndexQueryBuilder.newInstance()
      .withObject(new Doc(id: 2, data: "doc b"))
      .withIndexName(indexName)
      .withId("b")
      .build())
    template.refresh(indexName)
    TEST_WRITER.waitForTraces(6)
    TEST_WRITER.clear()

    and:
    def query = new NativeSearchQueryBuilder().withIndices(indexName).build()
    def hits = new AtomicLong()
    List<Map<String, Object>> results = []
    def bucketTags = [:]

    when:
    template.query(query, new ResultsExtractor<Doc>() {

        @Override
        Doc extract(SearchResponse response) {
          hits.addAndGet(response.getHits().totalHits())
          results.addAll(response.hits.collect { it.source })
          if (response.getAggregations() != null) {
            InternalNested internalNested = response.getAggregations().get("tag")
            if (internalNested != null) {
              Terms terms = internalNested.getAggregations().get("count_agg")
              Collection<Terms.Bucket> buckets = terms.getBuckets()
              for (Terms.Bucket bucket : buckets) {
                bucketTags.put(Integer.valueOf(bucket.getKeyAsString()), bucket.getDocCount())
              }
            }
          }
          return null
        }
      })

    then:
    hits.get() == 2
    results[0] == [id: "2", data: "doc b"]
    results[1] == [id: "1", data: "doc a"]
    bucketTags == [:]

    assertTraces(1) {
      trace(1) {
        span {
          serviceName service()
          resourceName "SearchAction"
          operationName operation()
          spanType DDSpanTypes.ELASTICSEARCH
          tags {
            "$Tags.COMPONENT" "elasticsearch-java"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "elasticsearch"
            "elasticsearch.action" "SearchAction"
            "elasticsearch.request" "SearchRequest"
            "elasticsearch.request.indices" indexName
            defaultTagsNoPeerService()
          }
        }
      }
    }

    cleanup:
    template.deleteIndex(indexName)

    where:
    indexName = "test-index-extract"
  }
}

class Elasticsearch2SpringTemplateV0Test extends Elasticsearch2SpringTemplateTest {

  @Override
  int version() {
    return 0
  }

  @Override
  String service() {
    return "elasticsearch"
  }

  @Override
  String operation() {
    return "elasticsearch.query"
  }
}

class Elasticsearch2SpringTemplateV1ForkedTest extends Elasticsearch2SpringTemplateTest {

  @Override
  int version() {
    return 1
  }

  @Override
  String service() {
    return datadog.trace.api.Config.get().getServiceName()
  }

  @Override
  String operation() {
    return "elasticsearch.query"
  }
}
