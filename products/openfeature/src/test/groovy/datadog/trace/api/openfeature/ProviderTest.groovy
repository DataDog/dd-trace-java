package datadog.trace.api.openfeature

import static datadog.trace.api.openfeature.Provider.DEFAULT_OPTIONS
import static java.util.concurrent.TimeUnit.MILLISECONDS

import datadog.trace.api.openfeature.Provider.Options
import datadog.trace.api.openfeature.evaluator.FeatureFlagEvaluator
import datadog.trace.test.util.DDSpecification
import dev.openfeature.sdk.EventProvider
import dev.openfeature.sdk.Features
import dev.openfeature.sdk.OpenFeatureAPI
import dev.openfeature.sdk.ProviderEvaluation
import dev.openfeature.sdk.ProviderEvent
import dev.openfeature.sdk.ProviderState
import dev.openfeature.sdk.Value
import dev.openfeature.sdk.exceptions.FatalError
import dev.openfeature.sdk.exceptions.ProviderNotReadyError
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import spock.lang.Shared
import spock.util.concurrent.PollingConditions

class ProviderTest extends DDSpecification {

  @Shared
  protected PollingConditions poll = new PollingConditions(timeout: 5)

  @Shared
  private ExecutorService executor

  void setup() {
    executor = Executors.newSingleThreadExecutor()
  }

  void cleanup() {
    executor.shutdownNow()
    OpenFeatureAPI.getInstance().shutdown()
  }

  void 'test set provider'() {
    given:
    final latch = new CountDownLatch(1)
    final initializer = Stub(Initializer) {
      init(_ as EventProvider, _ as long, _ as TimeUnit) >> { latch.await(it[1], it[2]) }
    }
    final provider = new Provider(DEFAULT_OPTIONS, initializer)
    final api = OpenFeatureAPI.getInstance()
    final client = api.getClient()

    when:
    api.setProvider(provider)

    then:
    client.getProviderState() == ProviderState.NOT_READY

    when:
    latch.countDown()

    then:
    poll.eventually {
      assert client.getProviderState() == ProviderState.READY
    }
  }

  void 'test set provider and wait'() {
    given:
    final latch = new CountDownLatch(1)
    final initializer = Stub(Initializer) {
      init(_ as EventProvider, _ as long, _ as TimeUnit) >> {
        latch.await(it[1], it[2])
      }
    }
    final provider = new Provider(DEFAULT_OPTIONS, initializer)
    final api = OpenFeatureAPI.getInstance()
    final client = api.getClient()

    when:
    executor.submit(() -> api.setProviderAndWait(provider))

    then:
    client.getProviderState() == ProviderState.NOT_READY

    when:
    latch.countDown()

    then:
    poll.eventually {
      assert client.getProviderState() == ProviderState.READY
    }
  }

  void 'test set provider and wait timeout'() {
    given:
    final latch = new CountDownLatch(1)
    final initializer = Stub(Initializer) {
      init(_ as EventProvider, _ as long, _ as TimeUnit) >> { latch.await(it[1], it[2]) }
    }
    final provider = new Provider(new Options().initTimeout(10, MILLISECONDS), initializer)
    final api = OpenFeatureAPI.getInstance()
    final client = api.getClient()
    final ready = new AtomicBoolean()
    client.on(ProviderEvent.PROVIDER_READY, { ready.set(true) })

    when:
    api.setProviderAndWait(provider)

    then:
    thrown(ProviderNotReadyError)
    !ready.get()
  }

  void 'test failure to load initializer'() {
    given:
    final initializer = Initializer.withReflection('i.do.not.exist')
    final provider = new Provider(DEFAULT_OPTIONS, initializer)
    final api = OpenFeatureAPI.getInstance()

    when:
    api.setProviderAndWait(provider)

    then:
    thrown(FatalError)
  }

  void 'test provider evaluation'() {
    given:
    final evaluatorMock = Mock(FeatureFlagEvaluator)
    final initializer = Stub(Initializer) {
      init(_ as EventProvider, _ as long, _ as TimeUnit) >> true
      evaluator() >> evaluatorMock
    }
    final provider = new Provider(DEFAULT_OPTIONS, initializer)
    final api = OpenFeatureAPI.getInstance()
    final client = api.getClient()
    api.setProviderAndWait(provider)

    when:
    closure.call(client, flag, defaultValue)

    then:
    1 * evaluatorMock.evaluate(_ as Class<?>, flag, defaultValue, _) >> {
      ProviderEvaluation.builder().value(defaultValue).reason('DEFAULT').build()
    }

    where:
    flag     | defaultValue | closure
    'bool'   | false        | Features::getBooleanDetails
    'string' | 'Hello!'     | Features::getStringDetails
    'int'    | 23           | Features::getIntegerDetails
    'double' | 3.14D        | Features::getDoubleDetails
    'object' | new Value()  | Features::getObjectDetails
  }
}
