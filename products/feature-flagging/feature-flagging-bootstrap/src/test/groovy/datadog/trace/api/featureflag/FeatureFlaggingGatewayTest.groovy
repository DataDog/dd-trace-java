package datadog.trace.api.featureflag

import datadog.trace.api.featureflag.exposure.ExposureEvent
import datadog.trace.api.featureflag.ufc.v1.ServerConfiguration
import spock.lang.Specification

class FeatureFlaggingGatewayTest extends Specification {

  void 'test attaching a config listener'() {
    given:
    def listener = Mock(FeatureFlaggingGateway.ConfigListener)
    final first = Stub(ServerConfiguration)
    final second =  Stub(ServerConfiguration)

    when:
    FeatureFlaggingGateway.addConfigListener(listener)
    FeatureFlaggingGateway.dispatch(first)

    then:
    1 * listener.accept(first)
    0 * _

    when:
    FeatureFlaggingGateway.dispatch(second)

    then:
    1 * listener.accept(second)
    0 * _


    cleanup:
    FeatureFlaggingGateway.removeConfigListener(listener)
  }

  void 'test attaching a listener after configured'() {
    given:
    def listener = Mock(FeatureFlaggingGateway.ConfigListener)
    final first = Stub(ServerConfiguration)

    when:
    FeatureFlaggingGateway.dispatch(first)
    FeatureFlaggingGateway.addConfigListener(listener)

    then:
    1 * listener.accept(first)
    0 * _

    cleanup:
    FeatureFlaggingGateway.removeConfigListener(listener)
  }

  void 'test attaching an exposure listener'() {
    given:
    def listener = Mock(FeatureFlaggingGateway.ExposureListener)
    final first = Stub(ExposureEvent)
    final second =  Stub(ExposureEvent)

    when:
    FeatureFlaggingGateway.addExposureListener(listener)
    FeatureFlaggingGateway.dispatch(first)

    then:
    1 * listener.accept(first)
    0 * _

    when:
    FeatureFlaggingGateway.dispatch(second)

    then:
    1 * listener.accept(second)
    0 * _

    cleanup:
    FeatureFlaggingGateway.removeExposureListener(listener)
  }
}
