package datadog.trace.civisibility.config;

import datadog.trace.civisibility.ipc.Serializer;
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
        EarlyFlakeDetectionSettingsSerializer::serializeExecutionsByDuration);
  }

  public static EarlyFlakeDetectionSettings deserialize(ByteBuffer buf) {
    boolean enabled = Serializer.readByte(buf) != 0;
    if (!enabled) {
      return EarlyFlakeDetectionSettings.DEFAULT;
    }

    int faultySessionThreshold = Serializer.readInt(buf);
    List<EarlyFlakeDetectionSettings.ExecutionsByDuration> executionsByDuration =
        Serializer.readList(
            buf, EarlyFlakeDetectionSettingsSerializer::deserializeExecutionsByDuration);
    return new EarlyFlakeDetectionSettings(enabled, executionsByDuration, faultySessionThreshold);
  }

  private static void serializeExecutionsByDuration(
      Serializer serializer,
      EarlyFlakeDetectionSettings.ExecutionsByDuration executionsByDuration) {
    serializer.write(executionsByDuration.durationMillis);
    serializer.write(executionsByDuration.executions);
  }

  private static EarlyFlakeDetectionSettings.ExecutionsByDuration deserializeExecutionsByDuration(
      ByteBuffer buf) {
    return new EarlyFlakeDetectionSettings.ExecutionsByDuration(
        Serializer.readLong(buf), Serializer.readInt(buf));
  }
}
