package springdata

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.checkpoints.CheckpointValidator
import datadog.trace.agent.test.checkpoints.CheckpointValidationMode
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.support.AbstractApplicationContext
import spock.lang.Retry
import spock.lang.Shared

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan

@Retry(count = 3, delay = 1000, mode = Retry.Mode.SETUP_FEATURE_CLEANUP)
class Elasticsearch53SpringRepositoryTest extends AgentTestRunner {
  // Setting up appContext & repo with @Shared doesn't allow
  // spring-data instrumentation to applied.
  // To change the timing without adding ugly checks everywhere -
  // use a dynamic proxy.  There's probably a more "groovy" way to do this.

  @Shared
  DocRepository repo = Proxy.newProxyInstance(
  getClass().getClassLoader(),
  [DocRepository, AutoCloseable] as Class[],
  new LazyProxyInvoker())

  static class LazyProxyInvoker implements InvocationHandler {
    def repo
    AbstractApplicationContext applicationContext

    DocRepository getOrCreateRepository() {
      if (repo != null) {
        return repo
      }

      applicationContext = new AnnotationConfigApplicationContext(Config)
      repo = applicationContext.getBean(DocRepository)

      return repo
    }

    @Override
    Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      if (method.name.equals("close")) {
        applicationContext.close()
        return null
      }
      return method.invoke(getOrCreateRepository(), args)
    }
  }

  def setupSpec() {
    repo.refresh() // lazy init
    cleanup()
  }

  def cleanupSpec() {
    cleanup()
    repo.close()
  }

  def cleanup() {
    def cleanupSpan = runUnderTrace("cleanup") {
      repo.refresh()
      repo.deleteAll()
      activeSpan()
    }
    TEST_WRITER.waitUntilReported(cleanupSpan)
  }

  def "test empty repo"() {
    setup:
    CheckpointValidator.excludeValidations_DONOTUSE_I_REPEAT_DO_NOT_USE(
      CheckpointValidationMode.INTERVALS,
      CheckpointValidationMode.THREAD_SEQUENCE)

    when:
    def result = repo.findAll()

    then:
    !result.iterator().hasNext()

    and:
    assertTraces(1) {
      sortSpansByStart()
      trace(2) {
        span {
          operationName "repository.operation"
          resourceName "CrudRepository.findAll"
          tags {
            "$Tags.COMPONENT" "spring-data"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            defaultTags()
          }
        }

        span {
          serviceName "elasticsearch"
          resourceName "SearchAction"
          operationName "elasticsearch.query"
          spanType DDSpanTypes.ELASTICSEARCH
          errored false
          childOf(span(0))

          tags {
            "$Tags.COMPONENT" "elasticsearch-java"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "elasticsearch"
            "elasticsearch.action" "SearchAction"
            "elasticsearch.request" "SearchRequest"
            "elasticsearch.request.indices" indexName
            "elasticsearch.request.search.types" "doc"
            defaultTags()
          }
        }
      }
    }

    where:
    indexName = "test-index"
  }

  def "test CRUD"() {
    setup:
    CheckpointValidator.excludeValidations_DONOTUSE_I_REPEAT_DO_NOT_USE(
      CheckpointValidationMode.INTERVALS,
      CheckpointValidationMode.THREAD_SEQUENCE)

    when:
    def doc = new Doc()

    then:
    repo.index(doc) == doc

    and:
    assertTraces(2) {
      sortSpansByStart()
      trace(3) {
        span {
          resourceName "ElasticsearchRepository.index"
          operationName "repository.operation"
          tags {
            "$Tags.COMPONENT" "spring-data"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            defaultTags()
          }
        }
        span {
          resourceName "IndexAction"
          operationName "elasticsearch.query"
          spanType DDSpanTypes.ELASTICSEARCH
          childOf(span(0))
          tags {
            "$Tags.COMPONENT" "elasticsearch-java"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "elasticsearch"
            "elasticsearch.action" "IndexAction"
            "elasticsearch.request" "IndexRequest"
            "elasticsearch.request.indices" indexName
            "elasticsearch.request.write.type" "doc"
            "elasticsearch.request.write.version"(-3)
            "elasticsearch.response.status" 201
            "elasticsearch.shard.replication.failed" 0
            "elasticsearch.shard.replication.successful" 1
            "elasticsearch.shard.replication.total" 2
            defaultTags()
          }
        }
        span {
          resourceName "RefreshAction"
          operationName "elasticsearch.query"
          spanType DDSpanTypes.ELASTICSEARCH
          childOf(span(0))
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
            defaultTags()
          }
        }
      }
      trace(1) {
        span {
          serviceName "elasticsearch"
          resourceName "PutMappingAction"
          operationName "elasticsearch.query"
          spanType DDSpanTypes.ELASTICSEARCH
          tags {
            "$Tags.COMPONENT" "elasticsearch-java"
            "$Tags.DB_TYPE" "elasticsearch"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "elasticsearch.action" "PutMappingAction"
            "elasticsearch.request" "PutMappingRequest"
            defaultTags()
          }
        }
      }
    }
    TEST_WRITER.clear()

    and:
    repo.findById("1").get() == doc

    and:
    assertTraces(1) {
      sortSpansByStart()
      trace(2) {
        span {
          resourceName "CrudRepository.findById"
          operationName "repository.operation"
          tags {
            "$Tags.COMPONENT" "spring-data"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            defaultTags()
          }
        }

        span {
          serviceName "elasticsearch"
          resourceName "GetAction"
          operationName "elasticsearch.query"
          spanType DDSpanTypes.ELASTICSEARCH
          childOf(span(0))
          tags {
            "$Tags.COMPONENT" "elasticsearch-java"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "elasticsearch"
            "elasticsearch.action" "GetAction"
            "elasticsearch.request" "GetRequest"
            "elasticsearch.request.indices" indexName
            "elasticsearch.type" "doc"
            "elasticsearch.id" "1"
            "elasticsearch.version" Number
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
    repo.findById("1").get() == doc

    and:
    assertTraces(2) {
      sortSpansByStart()
      trace(3) {
        span {
          resourceName "ElasticsearchRepository.index"
          operationName "repository.operation"
          tags {
            "$Tags.COMPONENT" "spring-data"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            defaultTags()
          }
        }
        span {
          resourceName "IndexAction"
          operationName "elasticsearch.query"
          spanType DDSpanTypes.ELASTICSEARCH
          childOf(span(0))
          tags {
            "$Tags.COMPONENT" "elasticsearch-java"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "elasticsearch"
            "elasticsearch.action" "IndexAction"
            "elasticsearch.request" "IndexRequest"
            "elasticsearch.request.indices" indexName
            "elasticsearch.request.write.type" "doc"
            "elasticsearch.request.write.version"(-3)
            "elasticsearch.response.status" 200
            "elasticsearch.shard.replication.failed" 0
            "elasticsearch.shard.replication.successful" 1
            "elasticsearch.shard.replication.total" 2
            defaultTags()
          }
        }
        span {
          resourceName "RefreshAction"
          operationName "elasticsearch.query"
          spanType DDSpanTypes.ELASTICSEARCH
          childOf(span(0))
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
            defaultTags()
          }
        }
      }
      trace(2) {
        span {
          resourceName "CrudRepository.findById"
          operationName "repository.operation"
          tags {
            "$Tags.COMPONENT" "spring-data"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            defaultTags()
          }
        }

        span {
          serviceName "elasticsearch"
          resourceName "GetAction"
          operationName "elasticsearch.query"
          spanType DDSpanTypes.ELASTICSEARCH
          childOf(span(0))
          tags {
            "$Tags.COMPONENT" "elasticsearch-java"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "elasticsearch"
            "elasticsearch.action" "GetAction"
            "elasticsearch.request" "GetRequest"
            "elasticsearch.request.indices" indexName
            "elasticsearch.type" "doc"
            "elasticsearch.id" "1"
            "elasticsearch.version" Number
            defaultTags()
          }
        }
      }
    }
    TEST_WRITER.clear()

    when:
    repo.deleteById("1")

    then:
    !repo.findAll().iterator().hasNext()

    and:
    assertTraces(2) {
      sortSpansByStart()
      trace(3) {
        span {
          resourceName "CrudRepository.deleteById"
          operationName "repository.operation"
          tags {
            "$Tags.COMPONENT" "spring-data"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            defaultTags()
          }
        }
        span {
          resourceName "DeleteAction"
          operationName "elasticsearch.query"
          spanType DDSpanTypes.ELASTICSEARCH
          childOf(span(0))
          tags {
            "$Tags.COMPONENT" "elasticsearch-java"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "elasticsearch"
            "elasticsearch.action" "DeleteAction"
            "elasticsearch.request" "DeleteRequest"
            "elasticsearch.request.indices" indexName
            "elasticsearch.request.write.type" "doc"
            "elasticsearch.request.write.version"(-3)
            "elasticsearch.shard.replication.failed" 0
            "elasticsearch.shard.replication.successful" 1
            "elasticsearch.shard.replication.total" 2
            defaultTags()
          }
        }
        span {
          resourceName "RefreshAction"
          operationName "elasticsearch.query"
          spanType DDSpanTypes.ELASTICSEARCH
          childOf(span(0))
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
            defaultTags()
          }
        }
      }

      trace(2) {
        span {
          resourceName "CrudRepository.findAll"
          operationName "repository.operation"
          tags {
            "$Tags.COMPONENT" "spring-data"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            defaultTags()
          }
        }
        span {
          serviceName "elasticsearch"
          resourceName "SearchAction"
          operationName "elasticsearch.query"
          spanType DDSpanTypes.ELASTICSEARCH
          childOf(span(0))
          tags {
            "$Tags.COMPONENT" "elasticsearch-java"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "elasticsearch"
            "elasticsearch.action" "SearchAction"
            "elasticsearch.request" "SearchRequest"
            "elasticsearch.request.indices" indexName
            "elasticsearch.request.search.types" "doc"
            defaultTags()
          }
        }
      }
    }

    where:
    indexName = "test-index"
  }
}
