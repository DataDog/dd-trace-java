package test

import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.DDSpan
import org.apache.ignite.IgniteCache
import org.apache.ignite.cache.eviction.lru.LruEvictionPolicy
import org.apache.ignite.cache.query.SqlFieldsQuery
import org.apache.ignite.cache.query.SqlQuery
import org.apache.ignite.cache.query.TextQuery
import org.apache.ignite.configuration.CacheConfiguration
import org.apache.ignite.configuration.NearCacheConfiguration
import spock.lang.Shared

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan

abstract class IgniteCacheSyncTest extends AbstractIgniteTest {

  @Shared IgniteCache cache

  def setup() {
    // Start with a fresh cache for each test
    cache = igniteClient.getOrCreateCache("testCache")
    def cleanupSpan = runUnderTrace("cleanup") {
      cache.clear()
      activeSpan()
    }
    TEST_WRITER.waitUntilReported(cleanupSpan as DDSpan)
    TEST_WRITER.start()
  }

  def "put command"() {
    when:
    cache.put("abc", "123")

    then:
    assertTraces(1) {
      trace(1) {
        assertIgniteCall(it, "cache.put", "testCache")
      }
    }
  }

  def "get command"() {
    when:
    cache.put("foo", "bar")
    def value = cache.get("foo")

    then:
    value == "bar"

    assertTraces(2) {
      trace(1) {
        assertIgniteCall(it, "cache.put", "testCache")
      }
      trace(1) {
        assertIgniteCall(it, "cache.get", "testCache")
      }
    }
  }

  def "size command"() {
    when:
    cache.put("foo", "bar")
    def size = cache.sizeLong()

    then:
    size == 1

    assertTraces(2) {
      trace(1) {
        assertIgniteCall(it, "cache.put", "testCache")
      }
      trace(1) {
        assertIgniteCall(it, "cache.size", "testCache")
      }
    }
  }

  def "sql fields query"() {
    when:
    def cacheConfig = new CacheConfiguration<UUID, Person>()
      .setName("Person")
      .setIndexedTypes(UUID, Person)
    IgniteCache<UUID, Person> personCache = igniteClient
      .getOrCreateCache(cacheConfig)

    final people = [
      new Person("Joe", 5),
      new Person("Emma", 17),
      new Person("John", 25),
      new Person("Sam", 95)
    ]

    // Add people to the cache
    people.each {personCache.put(it.getId(), it)}

    def sql = "select id from Person where age >= ?"
    def result = personCache.query(new SqlFieldsQuery(sql).setArgs(18)).getAll()

    then:
    def adults = people.findAll {it.getAge() >= 18}

    result.collect {it.get(0) } as Set == adults.collect {it.getId() } as Set


    assertTraces(people.size() + 1) {
      people.each {person ->
        trace(1) {
          assertIgniteCall(it, "cache.put", "Person")
        }
      }
      trace(1) {
        span {
          serviceName service()
          resourceName sql
          operationName operation()
          spanType DDSpanTypes.CACHE
          errored false
          tags {
            "$Tags.COMPONENT" "ignite-cache"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "ignite"
            "ignite.operation" "cache.query"
            "$Tags.DB_OPERATION" "SELECT"
            "ignite.cache.query_type" SqlFieldsQuery.getSimpleName()
            "ignite.cache.name" "Person"
            if (igniteClient.name()) {
              "ignite.instance" igniteClient.name()
            }
            "ignite.version" igniteClient.version().toString()
            defaultTagsNoPeerService()
          }
        }
      }
    }
  }

  def "sql query"() {
    when:
    def cacheConfig = new CacheConfiguration<UUID, Person>()
      .setName("Person")
      .setIndexedTypes(UUID, Person)
    IgniteCache<UUID, Person> personCache = igniteClient
      .getOrCreateCache(cacheConfig)

    final emma = new Person("Emma", 17)

    final people = [
      new Person("Joe", 5),
      emma,
      new Person("John", 25),
      new Person("Sam", 95)
    ]

    // Add people to the cache
    people.each {personCache.put(it.getId(), it)}

    def sql = "age >= ?"
    def query = new SqlQuery(Person, sql)
    query.setArgs(18)
    personCache.query(query).getAll()

    then:
    assertTraces(people.size() + 1) {
      people.each {person ->
        trace(1) {
          assertIgniteCall(it, "cache.put", "Person")
        }
      }
      trace(1) {
        span {
          serviceName service()
          resourceName "SELECT * FROM Person WHERE ${sql}"
          operationName operation()
          spanType DDSpanTypes.CACHE
          errored false
          tags {
            "$Tags.COMPONENT" "ignite-cache"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "ignite"
            "ignite.operation" "cache.query"
            "$Tags.DB_OPERATION" "SELECT"
            "ignite.cache.query_type" SqlQuery.getSimpleName()
            "ignite.cache.name" "Person"
            "ignite.cache.entity_type" "Person"
            if (igniteClient.name()) {
              "ignite.instance" igniteClient.name()
            }
            "ignite.version" igniteClient.version().toString()
            defaultTagsNoPeerService()
          }
        }
      }
    }
  }

  def "text query"() {
    when:
    def cacheConfig = new CacheConfiguration<UUID, Person>()
      .setName("PersonText")
      .setIndexedTypes(UUID, Person)
    IgniteCache<UUID, Person> personCache = igniteClient
      .getOrCreateCache(cacheConfig)

    final emma = new Person("Emma", 17)

    final people = [new Person("Joe", 5), emma, new Person("John", 25), new Person("Sam", 95)]

    // Add people to the cache
    people.each {personCache.put(it.getId(), it)}

    def result = personCache.query(new TextQuery(Person, "Emma")).getAll()

    then:

    (result.get(0) as Map.Entry<UUID, Person>).getValue().getId() == emma.getId()

    assertTraces(people.size() + 1) {
      people.each {person ->
        trace(1) {
          assertIgniteCall(it, "cache.put", "PersonText")
        }
      }
      trace(1) {
        span {
          serviceName service()
          resourceName "cache.query ${TextQuery.getSimpleName()} on PersonText"
          operationName operation()
          spanType DDSpanTypes.CACHE
          errored false
          tags {
            "$Tags.COMPONENT" "ignite-cache"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "ignite"
            "ignite.operation" "cache.query"
            "ignite.cache.query_type" TextQuery.getSimpleName()
            "ignite.cache.name" "PersonText"
            if (igniteClient.name()) {
              "ignite.instance" igniteClient.name()
            }
            "ignite.version" igniteClient.version().toString()
            defaultTagsNoPeerService()
          }
        }
      }
    }
  }


  def "multiple caches"() {
    setup:
    def caches = igniteClient.getOrCreateCaches(
      Arrays.asList(
      new CacheConfiguration<String, String>("multicache1"),
      new CacheConfiguration<String, String>("multicache2"),
      ))

    when:
    caches.each {it.put("foo", "bar")}

    then:
    assertTraces(2) {
      trace(1) {
        assertIgniteCall(it, "cache.put", "multicache1")
      }
      trace(1) {
        assertIgniteCall(it, "cache.put", "multicache2")
      }
    }

    cleanup:
    caches.each {it.close() }
  }

  def "near cache"() {
    setup:
    def farCacheConfig = new CacheConfiguration<String, String>("farCache")
    def nearCacheConfig = new NearCacheConfiguration<String, String>()
    nearCacheConfig.nearEvictionPolicy = new LruEvictionPolicy<>(10)
    farCacheConfig.setNearConfiguration(nearCacheConfig)

    def farCache = igniteServer.getOrCreateCache(farCacheConfig)
    def cleanupSpan = runUnderTrace("cleanup") {
      farCache.put("foo", "bar")
      activeSpan()
    }
    TEST_WRITER.waitUntilReported(cleanupSpan as DDSpan)
    TEST_WRITER.start()

    when:
    def nearCache = igniteClient.getOrCreateNearCache("farCache", nearCacheConfig)
    def result = nearCache.get("foo")

    then:
    result == "bar"
    assertTraces(1) {
      trace(1) {
        assertIgniteCall(it, "cache.get", "farCache")
      }
    }

    cleanup:
    farCache?.close()
    nearCache?.close()
  }
}

class IgniteCacheSyncV0ForkedTest extends IgniteCacheSyncTest {

  @Override
  int version() {
    return 0
  }

  @Override
  String service() {
    return AbstractIgniteTest.V0_SERVICE
  }

  @Override
  String operation() {
    return AbstractIgniteTest.V0_OPERATION
  }
}

class IgniteCacheSyncV1ForkedTest extends IgniteCacheSyncTest {

  @Override
  int version() {
    return 1
  }

  @Override
  String service() {
    return AbstractIgniteTest.V1_SERVICE
  }

  @Override
  String operation() {
    return AbstractIgniteTest.V1_OPERATION
  }
}
