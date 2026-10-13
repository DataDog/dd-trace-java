package datadog.trace.common.writer.ddintake;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.trace.api.Config;
import datadog.trace.api.intake.TrackType;
import datadog.trace.test.util.DDJavaSpecification;
import org.tabletest.junit.TableTest;

class DDIntakeTrackTypeResolverTest extends DDJavaSpecification {

  @TableTest({
    "scenario                           | ciVisibilityEnabled | ciVisibilityAgentlessEnabled | expectedTrackType",
    "ci-vis disabled agentless disabled | false               | false                        | NOOP             ",
    "ci-vis enabled agentless disabled  | true                | false                        | CITESTCYCLE      ",
    "ci-vis enabled agentless enabled   | true                | true                         | CITESTCYCLE      "
  })
  void shouldReturnTheCorrectTrackType(
      boolean ciVisibilityEnabled,
      boolean ciVisibilityAgentlessEnabled,
      TrackType expectedTrackType) {
    Config config = mock(Config.class);
    when(config.isCiVisibilityEnabled()).thenReturn(ciVisibilityEnabled);
    when(config.isCiVisibilityAgentlessEnabled()).thenReturn(ciVisibilityAgentlessEnabled);

    assertEquals(expectedTrackType, DDIntakeTrackTypeResolver.resolve(config));
  }
}
