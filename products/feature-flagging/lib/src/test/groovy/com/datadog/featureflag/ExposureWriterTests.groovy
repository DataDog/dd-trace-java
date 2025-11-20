package com.datadog.featureflag

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer
import static java.util.concurrent.TimeUnit.MILLISECONDS

import com.squareup.moshi.Moshi
import datadog.communication.ddagent.DDAgentFeaturesDiscovery
import datadog.communication.ddagent.SharedCommunicationObjects
import datadog.trace.agent.test.server.http.TestHttpServer
import datadog.trace.api.Config
import datadog.trace.api.IdGenerationStrategy
import datadog.trace.api.featureflag.FeatureFlaggingGateway
import datadog.trace.api.featureflag.exposure.Allocation
import datadog.trace.api.featureflag.exposure.ExposureEvent
import datadog.trace.api.featureflag.exposure.ExposuresRequest
import datadog.trace.api.featureflag.exposure.Flag
import datadog.trace.api.featureflag.exposure.Subject
import datadog.trace.api.featureflag.exposure.Variant
import datadog.trace.test.util.DDSpecification
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okio.Okio
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.util.concurrent.PollingConditions

class ExposureWriterTests extends DDSpecification {

  @Shared
  protected final Queue<ExposuresRequest> requests = new ConcurrentLinkedQueue<>()

  @Shared
  protected final Set<String> failed = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>())

  @Shared
  @AutoCleanup
  protected TestHttpServer server = httpServer {
    final adapter = new Moshi.Builder().build().adapter(ExposuresRequest)
    handlers {
      prefix("/evp_proxy/api/v2/exposures") {
        final exposuresRequest = adapter.fromJson(Okio.buffer(Okio.source(new ByteArrayInputStream(request.body))))
        final serviceName = exposuresRequest.context.service
        final failForever = serviceName == 'fail-forever'
        final fail = serviceName.startsWith('fail') && (failed.add(serviceName) || failForever)
        if (fail) {
          response.status(500).send('Boom!!!')
        } else {
          requests.add(exposuresRequest)
          response.status(200).send('OK')
        }
      }
    }
  }

  @Shared
  protected PollingConditions poll = new PollingConditions(timeout: 5)

  @Shared
  protected SharedCommunicationObjects sco = Stub(SharedCommunicationObjects) {
    featuresDiscovery(_ as Config) >> {
      return Mock(DDAgentFeaturesDiscovery) {
        supportsEvpProxy() >> true
        getEvpProxyEndpoint() >> '/evp_proxy/'
      }
    }
  }.tap {
    agentUrl = HttpUrl.get(server.address)
    agentHttpClient = new OkHttpClient.Builder().build()
  }

  void cleanup() {
    requests.clear()
    failed.clear()
  }

  void 'test exposure event writes'() {
    setup:
    def config = mockConfig(service, env, version)
    def exposures = (1..5).collect { buildExposure() }
    def writer = new ExposureWriterImpl(1 << 4, 100, MILLISECONDS, sco, config)
    writer.init()

    when:
    exposures.each { writer.accept(it) }

    then:
    poll.eventually {
      assert !requests.empty
      requests.each {
        assert it.context.service == service ?: 'unknown'
        if (env) {
          assert it.context.env == env
        }
        if (version) {
          assert it.context.version == version.toString()
        }
      }
      final received = requests*.exposures.flatten() as List<ExposureEvent>
      assertExposures(received, exposures)
    }

    cleanup:
    writer.close()

    where:
    service        | env    | version
    null           | null   | null
    'test-service' | 'test' | '23'
    'test-service' | null   | '23'
    'test-service' | 'test' | null
  }

  void 'test lru cache'() {
    setup:
    def config = mockConfig('test-service')
    def exposures = (0..5).collect { buildExposure() }
    def writer = new ExposureWriterImpl(1 << 4, 100, MILLISECONDS, sco, config)
    writer.init()

    when: 'populating the cache'
    exposures.each { writer.accept(it) }

    then: 'all events are written'
    new PollingConditions(timeout: 1).eventually {
      requests*.exposures.flatten().size() == exposures.size()
    }

    when: 'publishing duplicate events'
    exposures.each { writer.accept(it) }

    then: 'no events are written'
    MILLISECONDS.sleep(300) // wait until a flush happens
    requests*.exposures.flatten().size() == exposures.size()

    when: 'a new event is generated'
    writer.accept(buildExposure())

    then: 'oldest event is evicted and the new one is submitted'
    poll.eventually {
      requests*.exposures.flatten().size() == exposures.size() + 1
    }

    cleanup:
    writer.close()
  }

  void 'test high load scenario'() {
    setup:
    def config = mockConfig('test-service')
    def exposuresPerThread = 100
    def random = new Random()
    def threads = Runtime.runtime.availableProcessors()
    def executor = Executors.newFixedThreadPool(threads)
    def exposures = (1..(threads * exposuresPerThread)).collect {
      buildExposure()
    }
    def latch = new CountDownLatch(1)
    def writer = new ExposureWriterImpl(sco, config)
    writer.init()

    when:
    def futures = exposures.collate(exposuresPerThread).collect { partition ->
      executor.submit {
        latch.await()
        partition.each {
          MILLISECONDS.sleep(random.nextInt(2))
          writer.accept(it)
        }
        return true
      }
    }
    latch.countDown() // start threads

    then:
    futures.each { it.get() } // wait for all threads to finish
    poll.eventually {
      final received = requests*.exposures.flatten() as List<ExposureEvent>
      assertExposures(received, exposures)
    }

    cleanup:
    writer.close()
    executor.shutdownNow()
  }

  void 'test failures are retried'() {
    setup:
    def config = mockConfig(serviceName)
    def writer = new ExposureWriterImpl(1 << 4, 100, MILLISECONDS, sco, config)
    writer.init()

    when:
    writer.accept(buildExposure())

    then:
    MILLISECONDS.sleep(500) // wait for a flush to happen
    final found = requests.find { it.context.service == serviceName }
    if (finallyFail) {
      assert found == null: requests
    } else {
      assert found != null: requests
    }

    cleanup:
    writer.close()

    where:
    serviceName    | finallyFail
    'fail-once'    | false
    'fail-forever' | true
  }

  void 'test writer stops receiving exposures if evp proxy is not available'() {
    given:
    final sco = Stub(SharedCommunicationObjects) {
      featuresDiscovery(_ as Config) >> {
        return Mock(DDAgentFeaturesDiscovery) {
          supportsEvpProxy() >> false
        }
      }
    }
    def writer = new ExposureWriterImpl(sco, Config.get())

    when:
    writer.init()

    then:
    poll.eventually {
      assert !writer.serializerThread.isAlive()
    }

    when:
    FeatureFlaggingGateway.dispatch(buildExposure())

    then:
    writer.queue.size() == 0

    cleanup:
    writer.close()
  }

  private Config mockConfig(String serviceName, String env = 'test', String version = '0.0.0') {
    return Mock(Config) {
      getIdGenerationStrategy() >> IdGenerationStrategy.fromName("RANDOM")
      getServiceName() >> serviceName
      getEnv() >> env
      getVersion() >> version
    }
  }

  private static void assertExposures(final List<ExposureEvent> receivedExposures, final List<ExposureEvent> expectedExposures) {
    assert receivedExposures.size() == expectedExposures.size()
    final received = new TreeSet<ExposureEvent>(ExposureWriterTests::compare)
    received.addAll(expectedExposures)
    assert received.containsAll(expectedExposures)
  }

  private static int compare(final ExposureEvent a, final ExposureEvent b) {
    if (a.is(b)) {
      return 0
    }
    if (a == null) {
      return -1
    }
    if (b == null) {
      return 1
    }

    def result = a.timestamp <=> b.timestamp
    if (result) {
      return result
    }

    result = (a.flag?.key ?: '') <=> (b.flag?.key ?: '')
    if (result) {
      return result
    }

    result = (a.variant?.key ?: '') <=> (b.variant?.key ?: '')
    if (result) {
      return result
    }

    result = (a.allocation?.key ?: '') <=> (b.allocation?.key ?: '')
    if (result) {
      return result
    }

    result = (a.subject?.id ?: '') <=> (b.subject?.id ?: '')
    if (result) {
      return result
    }

    final aEntry = a.subject?.attributes?.entrySet()?.iterator()?.next()
    final bEntry = b.subject?.attributes?.entrySet()?.iterator()?.next()
    result = (aEntry?.key ?: '') <=> (bEntry?.key ?: '')
    if (result) {
      return result
    }
    return (aEntry?.value?.toString() ?: '') <=> (bEntry?.value?.toString() ?: '')
  }

  private static ExposureEvent buildExposure() {
    final idx = UUID.randomUUID().toString()
    return new ExposureEvent(
      System.currentTimeMillis(),
      new Allocation("Allocation_$idx"),
      new Flag("Flag_$idx"),
      new Variant("Variant_$idx"),
      new Subject("Subject_$idx", [("key_$idx".toString()): "value_$idx".toString()])
      )
  }
}
