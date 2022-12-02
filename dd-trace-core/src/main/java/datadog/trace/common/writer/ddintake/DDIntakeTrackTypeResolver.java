package datadog.trace.common.writer.ddintake;

import datadog.trace.api.Config;
import datadog.trace.api.intake.TrackType;

public final class DDIntakeTrackTypeResolver {

  public static TrackType resolve(final Config config) {
    if (config.isCiVisibilityEnabled()) {
      return TrackType.CITESTCYCLE;
    } else {
      return TrackType.NOOP;
    }
  }
}
