package datadog.trace.civisibility.config;

import datadog.trace.civisibility.ipc.serialization.Serializer;
import java.nio.ByteBuffer;
import java.util.List;

public final class EarlyFlakeDetectionSettingsSerializer {

  public static void serialize(Serializer serializer, EarlyFlakeDetectionSettings settings) {
    if (!settings.isEnabled()) {
      serializer.write((byte) 0);
      return;
    }
    serializer.write((byte) 1);
    serializer.write(settings.getFaultySessionThreshold());
    serializer.write(
        settings.getExecutionsByDuration(),
        ExecutionsByDuration.ExecutionsByDurationSerializer::serialize);
  }

  public static EarlyFlakeDetectionSettings deserialize(ByteBuffer buf) {
    boolean enabled = Serializer.readByte(buf) != 0;
    if (!enabled) {
      return EarlyFlakeDetectionSettings.DEFAULT;
    }

    int faultySessionThreshold = Serializer.readInt(buf);
    List<ExecutionsByDuration> executionsByDuration =
        Serializer.readList(buf, ExecutionsByDuration.ExecutionsByDurationSerializer::deserialize);
    return new EarlyFlakeDetectionSettings(enabled, executionsByDuration, faultySessionThreshold);
  }
}
