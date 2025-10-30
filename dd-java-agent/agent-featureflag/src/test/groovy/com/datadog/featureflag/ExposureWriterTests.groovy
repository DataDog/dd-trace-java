package com.datadog.featureflag

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer

import com.datadog.featureflag.exposure.ExposureEvent
import com.datadog.featureflag.exposure.ExposuresRequest
import com.datadog.featureflag.utils.TestUtils
import com.squareup.moshi.Moshi
import datadog.trace.agent.test.server.http.TestHttpServer
import datadog.trace.api.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl
import okio.Okio
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.util.concurrent.PollingConditions

class ExposureWriterTests extends AbstractJsonTestSuiteBasedTests {

  @Shared
  protected List<ExposuresRequest> exposures = new CopyOnWriteArrayList<>()

  @Shared
  @AutoCleanup
  protected TestHttpServer server = httpServer {
    final adapter = new Moshi.Builder().build().adapter(ExposuresRequest)
    handlers {
      prefix("/evp_proxy/v2/api/v2/exposures") {
        final request = adapter.fromJson(Okio.buffer(Okio.source(new ByteArrayInputStream(request.body))))
        exposures.add(request)
        response.status(200).send('')
      }
    }
  }

  void cleanup() {
    exposures.clear()
  }

  void 'test exposure adapter'() {
    setup:
    def writer = Mock(ExposureWriter)
    def evaluator = new FeatureFlagEvaluatorImpl()
    evaluator.accept(configuration)
    evaluator = new ExposureWriterEvaluatorAdapter(writer, evaluator)
    final context = buildContext(evaluation)

    when:
    evaluateDetails(evaluator, evaluation, context)

    then:
    if (evaluation.result.flagMetadata?.doLog) {
      1 * writer.write({ event -> exposureMatches(event, evaluation) })
    } else {
      0 * writer.write(_)
    }

    where:
    evaluation << testCases
  }

  void 'test lru cache'() {
    setup:
    def writer = Mock(ExposureWriter)
    final cacheCapacity = 5
    def evaluator = new FeatureFlagEvaluatorImpl()
    evaluator.accept(configuration)
    evaluator = new ExposureWriterEvaluatorAdapter(cacheCapacity, writer, evaluator)

    when: 'populating the cache'
    testCases.subList(0, cacheCapacity).each { evaluateDetails(evaluator, it, buildContext(it)) }

    then: 'all events are written'
    cacheCapacity * writer.write(_)

    when: 'publishing duplicate events'
    testCases.subList(0, cacheCapacity).each { evaluateDetails(evaluator, it, buildContext(it)) }

    then: 'no events are written'
    0 * writer.write(_)

    when: 'a new event is generated'
    final newEvaluation = testCases.last()
    evaluateDetails(evaluator, newEvaluation, buildContext(newEvaluation))

    then: 'oldest event is evicted and the new one is submitted'
    1 * writer.write(_)
  }

  void 'test evp proxy writes'() {
    setup:
    injectSysConfig('service', 'test-exposure-service')
    injectSysConfig('env', 'test')
    def writer = new ExposureWriterImpl(100_000, 100L, TimeUnit.MILLISECONDS, HttpUrl.get(server.address), Config.get())
    writer.init()
    def evaluator = new FeatureFlagEvaluatorImpl()
    evaluator.accept(configuration)
    evaluator = new ExposureWriterEvaluatorAdapter(writer, evaluator)
    def evaluations = testCases.findAll {
      it.result?.flagMetadata?.doLog == true
    }.take(10)

    when:
    evaluations.each {
      evaluateDetails(evaluator, it, buildContext(it))
    }

    then:
    new PollingConditions(timeout: 1).eventually {
      assert !exposures.empty
      assert exposures.every {
        it.context.service == 'test-exposure-service'
        && it.context.env == 'test'
      }

      final events = exposures*.events.flatten() as List<ExposureEvent>
      assert events.size() == evaluations.size()
      assert evaluations.every {
        evaluation ->
        events.find {
          event -> exposureMatches(event, evaluation)
        } != null
      }
    }

    cleanup:
    writer.close()
  }

  private static boolean exposureMatches(final ExposureEvent event, final TestUtils.EvaluationTest evaluation) {
    return event.flag.key == evaluation.flag
    && event.allocation.key == evaluation.result.flagMetadata.allocationKey
    && event.variant.key == evaluation.result.variant
    && event.subject.id == evaluation.targetingKey
    && evaluation.attributes.every {
      event.subject.attributes[it.key] == it.value
    }
  }
}
