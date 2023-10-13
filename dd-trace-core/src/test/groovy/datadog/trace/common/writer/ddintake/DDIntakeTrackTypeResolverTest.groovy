package datadog.trace.common.writer.ddintake

import datadog.trace.api.Config
import datadog.trace.api.intake.TrackType
import datadog.trace.test.util.DDSpecification

class DDIntakeTrackTypeResolverTest extends DDSpecification {

  def "should return the correct TrackType"() {
    setup:
    Config config = Mock(Config)
    config.ciVisibilityEnabled >> ciVisibilityEnabled
    config.ciVisibilityAgentlessEnabled >> ciVisibilityAgentlessEnabled

    expect:
    DDIntakeTrackTypeResolver.resolve(config) == expectedTrackType

    where:
    ciVisibilityEnabled | ciVisibilityAgentlessEnabled | expectedTrackType
    false | false | TrackType.NOOP
    true | false | TrackType.CITESTCYCLE
    true | true | TrackType.CITESTCYCLE
  }
}
