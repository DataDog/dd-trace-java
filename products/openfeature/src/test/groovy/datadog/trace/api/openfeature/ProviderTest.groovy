package datadog.trace.api.openfeature


import static java.util.concurrent.TimeUnit.MILLISECONDS

import datadog.trace.api.openfeature.Provider.Options
import datadog.trace.api.openfeature.config.ufc.v1.ServerConfiguration
import datadog.trace.test.util.DDSpecification
import dev.openfeature.sdk.Features
import dev.openfeature.sdk.OpenFeatureAPI
import dev.openfeature.sdk.ProviderEvent
import dev.openfeature.sdk.ProviderState
import dev.openfeature.sdk.Value
import dev.openfeature.sdk.exceptions.ProviderNotReadyError
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
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
    final provider = new Provider()
    final api = OpenFeatureAPI.getInstance()
    final client = api.getClient()

    when:
    api.setProvider(provider)

    then:
    client.getProviderState() == ProviderState.NOT_READY

    when:
    provider.onConfiguration(Mock(ServerConfiguration))

    then:
    poll.eventually {
      assert client.getProviderState() == ProviderState.READY
    }
  }

  void 'test set provider and wait'() {
    given:
    final provider = new Provider()
    final api = OpenFeatureAPI.getInstance()
    final client = api.getClient()

    when:
    executor.submit(() -> api.setProviderAndWait(provider))

    then:
    client.getProviderState() == ProviderState.NOT_READY

    when:
    provider.onConfiguration(Mock(ServerConfiguration))

    then:
    poll.eventually {
      assert client.getProviderState() == ProviderState.READY
    }
  }

  void 'test set provider and wait timeout'() {
    given:
    final provider = new Provider(new Options().initTimeout(10, MILLISECONDS))
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

  void 'test provider evaluation'() {
    given:
    final provider = new Provider()
    provider.onConfiguration(Mock(ServerConfiguration))
    final api = OpenFeatureAPI.getInstance()
    final client = api.getClient()
    api.setProviderAndWait(provider)

    when:
    final result = closure.call(client, flag, defaultValue)

    then:
    result != null

    where:
    flag     | defaultValue | closure
    'bool'   | false        | Features::getBooleanDetails
    'string' | 'Hello!'     | Features::getStringDetails
    'int'    | 23           | Features::getIntegerDetails
    'double' | 3.14D        | Features::getDoubleDetails
    'object' | new Value()  | Features::getObjectDetails
  }
}
