package datadog.trace.common.writer.ddintake;

import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.api.Config;
import datadog.trace.api.intake.TrackType;
import datadog.trace.core.test.DDCoreSpecification;
import org.junit.jupiter.params.ParameterizedTest;
import org.tabletest.junit.TableTest;
import org.tabletest.junit.TypeConverter;
import org.tabletest.junit.TypeConverterSources;

@TypeConverterSources(DDIntakeTrackTypeResolverTest.Converters.class)
class DDIntakeTrackTypeResolverTest extends DDCoreSpecification {

  public static final class Converters {
    @TypeConverter
    public static TrackType toTrackType(String value) {
      switch (value.trim()) {
        case "TrackType.NOOP":
          return TrackType.NOOP;
        case "TrackType.CITESTCYCLE":
          return TrackType.CITESTCYCLE;
        default:
          return TrackType.valueOf(value.trim());
      }
    }
  }

  @TableTest({
    "scenario                                 | ciVisibilityEnabled | expectedTrackType    ",
    "ci visibility disabled                   | false               | TrackType.NOOP       ",
    "ci visibility enabled agentless disabled | true                | TrackType.CITESTCYCLE",
    "ci visibility enabled agentless enabled  | true                | TrackType.CITESTCYCLE"
  })
  @ParameterizedTest(name = "[{index}] should return the correct TrackType")
  void shouldReturnTheCorrectTrackType(boolean ciVisibilityEnabled, TrackType expectedTrackType) {
    if (ciVisibilityEnabled) {
      injectSysConfig("dd.civisibility.enabled", "true");
    }
    assertEquals(expectedTrackType, DDIntakeTrackTypeResolver.resolve(Config.get()));
  }
}
