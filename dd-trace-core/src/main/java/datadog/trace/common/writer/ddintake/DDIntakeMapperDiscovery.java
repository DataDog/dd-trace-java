package datadog.trace.common.writer.ddintake;

import datadog.trace.api.Config;
import datadog.trace.api.WellKnownTags;
import datadog.trace.api.intake.TrackType;
import datadog.trace.civisibility.writer.ddintake.CiTestCycleMapperV1;
import datadog.trace.common.writer.RemoteMapper;
import datadog.trace.common.writer.RemoteMapperDiscovery;

/**
 * Mapper discovery logic when a DDIntake is used. The mapper is discovered based on a backend
 * TrackType. If the TrackType is not supported, it returns a Noop Mapper. Typically, an instance of
 * this class is used during the mapper lazy loading in the {@code PayloadDispatcher} class.
 */
public class DDIntakeMapperDiscovery implements RemoteMapperDiscovery {

  private final TrackType trackType;
  private final WellKnownTags wellKnownTags;

  private RemoteMapper mapper;

  public DDIntakeMapperDiscovery(final TrackType trackType) {
    this.trackType = trackType;
    this.wellKnownTags = Config.get().getWellKnownTags();
  }

  public DDIntakeMapperDiscovery(final TrackType trackType, final WellKnownTags wellKnownTags) {
    this.trackType = trackType;
    this.wellKnownTags = wellKnownTags;
  }

  private void reset() {
    this.mapper = null;
  }

  @Override
  public void discover() {
    reset();
    if (TrackType.CITESTCYCLE.equals(trackType)) {
      this.mapper = new CiTestCycleMapperV1(wellKnownTags);
    } else {
      this.mapper = RemoteMapper.NO_OP;
    }
  }

  @Override
  public RemoteMapper getMapper() {
    return this.mapper;
  }
}
