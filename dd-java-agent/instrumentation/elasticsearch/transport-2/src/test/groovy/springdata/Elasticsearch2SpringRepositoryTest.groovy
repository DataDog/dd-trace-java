package springdata


import datadog.trace.agent.test.naming.VersionedNamingTestBase
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.test.util.Flaky
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import spock.lang.Shared

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan

@Flaky
abstract class Elasticsearch2SpringRepositoryTest extends VersionedNamingTestBase {
  @Shared
  ApplicationContext applicationContext = new AnnotationConfigApplicationContext(Config)

  @Shared
  DocRepository repo = applicationContext.getBean(DocRepository)

  def cleanup() {
    def cleanupSpan = runUnderTrace("cleanup") {
      repo.refresh()
      repo.deleteAll()
      activeSpan()
    }
    TEST_WRITER.waitUntilReported(cleanupSpan)
  }

  def setupSpec() {
    // force putting the mapping here to avoid flakiness
    repo.index(new Doc())
    cleanup()
  }

  def cleanupSpec() {
    applicationContext?.close()
  }

  def "test empty repo"() {
    setup:

    when:
    def result = repo.findAll()

    then:
    !result.iterator().hasNext()

    and:
    assertTraces(1) {
      trace(1) {
        span {
          serviceName service()
          resourceName "SearchAction"
          operationName operation()
          spanType DDSpanTypes.ELASTICSEARCH
          errored false
          tags {
            "$Tags.COMPONENT" "elasticsearch-java"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "elasticsearch"
            "elasticsearch.action" "SearchAction"
            "elasticsearch.request" "SearchRequest"
            "elasticsearch.request.indices" indexName
            "elasticsearch.request.search.types" "doc"
            defaultTagsNoPeerService()
          }
        }
      }
    }

    where:
    indexName = "test-index"
  }

  def "test CRUD"() {
    setup:

    when:
    def doc = new Doc()

    then:
    repo.index(doc) == doc

    and:
    assertTraces(2) {
      trace(1) {
        span {
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
            "elasticsearch.request.write.type" "doc"
            peerServiceFrom(Tags.PEER_HOSTNAME)
            defaultTags()
          }
        }
      }
      trace(1) {
        span {
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
    }
    TEST_WRITER.clear()

    and:
    repo.findOne("1") == doc

    and:
    assertTraces(1) {
      trace(1) {
        span {
          serviceName service()
          resourceName "GetAction"
          operationName operation()
          spanType DDSpanTypes.ELASTICSEARCH
          tags {
            "$Tags.COMPONENT" "elasticsearch-java"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.PEER_HOSTNAME" "local"
            "$Tags.PEER_HOST_IPV4" "0.0.0.0"
            "$Tags.DB_TYPE" "elasticsearch"
            "elasticsearch.action" "GetAction"
            "elasticsearch.request" "GetRequest"
            "elasticsearch.request.indices" indexName
            "elasticsearch.type" "doc"
            "elasticsearch.id" "1"
            "elasticsearch.version" Number
            peerServiceFrom(Tags.PEER_HOSTNAME)
            defaultTags()
          }
        }
      }
    }
    TEST_WRITER.clear()

    when:
    doc.data = "other data"

    then:
    repo.index(doc) == doc
    repo.findOne("1") == doc

    and:
    assertTraces(3) {
      trace(1) {
        span {
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
            "elasticsearch.request.write.type" "doc"
            peerServiceFrom(Tags.PEER_HOSTNAME)
            defaultTags()
          }
        }
      }
      trace(1) {
        span {
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
          resourceName "GetAction"
          operationName operation()
          spanType DDSpanTypes.ELASTICSEARCH
          tags {
            "$Tags.COMPONENT" "elasticsearch-java"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.PEER_HOSTNAME" "local"
            "$Tags.PEER_HOST_IPV4" "0.0.0.0"
            "$Tags.DB_TYPE" "elasticsearch"
            "elasticsearch.action" "GetAction"
            "elasticsearch.request" "GetRequest"
            "elasticsearch.request.indices" indexName
            "elasticsearch.type" "doc"
            "elasticsearch.id" "1"
            "elasticsearch.version" Number
            peerServiceFrom(Tags.PEER_HOSTNAME)
            defaultTags()
          }
        }
      }
    }
    TEST_WRITER.clear()

    when:
    repo.delete("1")

    then:
    !repo.findAll().iterator().hasNext()

    and:
    assertTraces(3) {
      trace(1) {
        span {
          resourceName "DeleteAction"
          operationName operation()
          spanType DDSpanTypes.ELASTICSEARCH
          tags {
            "$Tags.COMPONENT" "elasticsearch-java"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.PEER_HOSTNAME" "local"
            "$Tags.PEER_HOST_IPV4" "0.0.0.0"
            "$Tags.DB_TYPE" "elasticsearch"
            "elasticsearch.action" "DeleteAction"
            "elasticsearch.request" "DeleteRequest"
            "elasticsearch.request.indices" indexName
            "elasticsearch.request.write.type" "doc"
            peerServiceFrom(Tags.PEER_HOSTNAME)
            defaultTags()
          }
        }
      }
      trace(1) {
        span {
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
            "elasticsearch.request.search.types" "doc"
            defaultTagsNoPeerService()
          }
        }
      }
    }

    where:
    indexName = "test-index"
  }
}

class Elasticsearch2SpringRepositoryV0Test extends Elasticsearch2SpringRepositoryTest {

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

class Elasticsearch2SpringRepositoryV1ForkedTest extends Elasticsearch2SpringRepositoryTest {

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
