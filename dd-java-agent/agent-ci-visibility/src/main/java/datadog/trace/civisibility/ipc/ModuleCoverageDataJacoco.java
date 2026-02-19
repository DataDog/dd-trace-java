package datadog.trace.civisibility.ipc;

import datadog.trace.api.DDTraceId;
import datadog.trace.civisibility.ipc.serialization.Serializer;
import java.nio.ByteBuffer;
import java.util.Arrays;
import javax.annotation.Nonnull;
import datadog.trace.util.HashingUtils;

public class ModuleCoverageDataJacoco extends ModuleSignal {

  @Nonnull private final byte[] coverageData;

  public ModuleCoverageDataJacoco(
      DDTraceId sessionId, long moduleId, @Nonnull byte[] coverageData) {
    super(sessionId, moduleId);
    this.coverageData = coverageData;
  }

  @Nonnull
  public byte[] getCoverageData() {
    return coverageData;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    ModuleCoverageDataJacoco that = (ModuleCoverageDataJacoco) o;
    return sessionId.toLong() == that.sessionId.toLong()
        && sessionId.toHighOrderLong() == that.sessionId.toHighOrderLong()
        && moduleId == that.moduleId
        && Arrays.equals(coverageData, that.coverageData);
  }

  @Override
  public int hashCode() {
    return HashingUtils.hash(sessionId, moduleId, Arrays.hashCode(coverageData));
  }

  @Override
  public String toString() {
    return "ModuleCoverageDataJacoco{" + "sessionId=" + sessionId + ", moduleId=" + moduleId + '}';
  }

  @Override
  public SignalType getType() {
    return SignalType.MODULE_COVERAGE_DATA_JACOCO;
  }

  @Override
  public ByteBuffer serialize() {
    Serializer s = new Serializer();
    s.write(sessionId.toHexString());
    s.write(moduleId);
    s.write(coverageData);
    return s.flush();
  }

  public static ModuleCoverageDataJacoco deserialize(ByteBuffer buffer) {
    DDTraceId sessionId = DDTraceId.fromHex(Serializer.readString(buffer));
    long moduleId = Serializer.readLong(buffer);
    byte[] coverageData = Serializer.readByteArray(buffer);
    return new ModuleCoverageDataJacoco(sessionId, moduleId, coverageData);
  }
}
