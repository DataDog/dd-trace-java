package datadog.trace.common.writer.ddintake;

import datadog.trace.api.civisibility.CiVisibilityWellKnownTags;
import datadog.trace.api.intake.TrackType;
import datadog.trace.civisibility.writer.ddintake.CiTestCovMapperV2;
import datadog.trace.civisibility.writer.ddintake.CiTestCycleMapperV1;
import datadog.trace.common.writer.RemoteMapper;
import datadog.trace.common.writer.RemoteMapperDiscovery;
import datadog.trace.llmobs.writer.ddintake.LLMObsSpanMapper;

/**
 * Mapper discovery logic when a DDIntake is used. The mapper is discovered based on a backend
 * TrackType. If the TrackType is not supported, it returns a Noop Mapper. Typically, an instance of
 * this class is used during the mapper lazy loading in the {@code PayloadDispatcher} class.
 */
public class DDIntakeMapperDiscovery implements RemoteMapperDiscovery {

  private final TrackType trackType;
  private final CiVisibilityWellKnownTags wellKnownTags;
  private final boolean compressionEnabled;

  private RemoteMapper mapper;

  public DDIntakeMapperDiscovery(
      final TrackType trackType,
      final CiVisibilityWellKnownTags wellKnownTags,
      boolean compressionEnabled) {
    this.trackType = trackType;
    this.wellKnownTags = wellKnownTags;
    this.compressionEnabled = compressionEnabled;
  }

  private void reset() {
    mapper = null;
  }

  @Override
  public void discover() {
    reset();
    if (TrackType.CITESTCYCLE.equals(trackType)) {
      mapper = new CiTestCycleMapperV1(wellKnownTags, compressionEnabled);
    } else if (TrackType.CITESTCOV.equals(trackType)) {
      mapper = new CiTestCovMapperV2(compressionEnabled);
    } else if (TrackType.LLMOBS.equals(trackType)) {
      mapper = new LLMObsSpanMapper();
    } else {
      mapper = RemoteMapper.NO_OP;
    }
  }

  @Override
  public RemoteMapper getMapper() {
    return mapper;
  }
}
