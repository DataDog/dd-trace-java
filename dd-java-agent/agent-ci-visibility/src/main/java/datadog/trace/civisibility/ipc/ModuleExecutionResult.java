package datadog.trace.civisibility.ipc;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;
import javax.annotation.Nullable;

public class ModuleExecutionResult implements Signal {

  private static final int COVERAGE_ENABLED_FLAG = 1;
  private static final int ITR_ENABLED_FLAG = 2;
  private static final int EARLY_FLAKE_DETECTION_ENABLED_FLAG = 4;
  private static final int EARLY_FLAKE_DETECTION_FAULTY_FLAG = 8;

  private final long sessionId;
  private final long moduleId;
  private final boolean coverageEnabled;
  private final boolean itrEnabled;
  private final boolean earlyFlakeDetectionEnabled;
  private final boolean earlyFlakeDetectionFaulty;
  private final long testsSkippedTotal;
  private final Collection<TestFramework> testFrameworks;
  @Nullable private final byte[] coverageData;

  public ModuleExecutionResult(
      long sessionId,
      long moduleId,
      boolean coverageEnabled,
      boolean itrEnabled,
      boolean earlyFlakeDetectionEnabled,
      boolean earlyFlakeDetectionFaulty,
      long testsSkippedTotal,
      Collection<TestFramework> testFrameworks,
      @Nullable byte[] coverageData) {
    this.sessionId = sessionId;
    this.moduleId = moduleId;
    this.coverageEnabled = coverageEnabled;
    this.itrEnabled = itrEnabled;
    this.earlyFlakeDetectionEnabled = earlyFlakeDetectionEnabled;
    this.earlyFlakeDetectionFaulty = earlyFlakeDetectionFaulty;
    this.testsSkippedTotal = testsSkippedTotal;
    this.testFrameworks = testFrameworks;
    this.coverageData = coverageData;
  }

  public long getSessionId() {
    return sessionId;
  }

  public long getModuleId() {
    return moduleId;
  }

  public boolean isCoverageEnabled() {
    return coverageEnabled;
  }

  public boolean isItrEnabled() {
    return itrEnabled;
  }

  public boolean isEarlyFlakeDetectionEnabled() {
    return earlyFlakeDetectionEnabled;
  }

  public boolean isEarlyFlakeDetectionFaulty() {
    return earlyFlakeDetectionFaulty;
  }

  public long getTestsSkippedTotal() {
    return testsSkippedTotal;
  }

  public Collection<TestFramework> getTestFrameworks() {
    return testFrameworks;
  }

  @Nullable
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
    ModuleExecutionResult that = (ModuleExecutionResult) o;
    return sessionId == that.sessionId
        && moduleId == that.moduleId
        && coverageEnabled == that.coverageEnabled
        && itrEnabled == that.itrEnabled
        && testsSkippedTotal == that.testsSkippedTotal
        && Objects.equals(testFrameworks, that.testFrameworks)
        && Arrays.equals(coverageData, that.coverageData);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        sessionId,
        moduleId,
        coverageEnabled,
        itrEnabled,
        testsSkippedTotal,
        testFrameworks,
        Arrays.hashCode(coverageData));
  }

  @Override
  public String toString() {
    return "ModuleExecutionResult{"
        + "sessionId="
        + sessionId
        + ", moduleId="
        + moduleId
        + ", coverageEnabled="
        + coverageEnabled
        + ", itrEnabled="
        + itrEnabled
        + ", itrTestsSkipped="
        + testsSkippedTotal
        + '}';
  }

  @Override
  public SignalType getType() {
    return SignalType.MODULE_EXECUTION_RESULT;
  }

  @Override
  public ByteBuffer serialize() {
    Serializer s = new Serializer();
    s.write(sessionId);
    s.write(moduleId);

    byte flags = 0;
    if (coverageEnabled) {
      flags |= COVERAGE_ENABLED_FLAG;
    }
    if (itrEnabled) {
      flags |= ITR_ENABLED_FLAG;
    }
    if (earlyFlakeDetectionEnabled) {
      flags |= EARLY_FLAKE_DETECTION_ENABLED_FLAG;
    }
    if (earlyFlakeDetectionFaulty) {
      flags |= EARLY_FLAKE_DETECTION_FAULTY_FLAG;
    }
    s.write(flags);

    s.write(testsSkippedTotal);
    s.write(testFrameworks, TestFramework::serialize);
    s.write(coverageData);

    return s.flush();
  }

  public static ModuleExecutionResult deserialize(ByteBuffer buffer) {
    long sessionId = Serializer.readLong(buffer);
    long moduleId = Serializer.readLong(buffer);

    int flags = Serializer.readByte(buffer);
    boolean coverageEnabled = (flags & COVERAGE_ENABLED_FLAG) != 0;
    boolean itrEnabled = (flags & ITR_ENABLED_FLAG) != 0;
    boolean earlyFlakeDetectionEnabled = (flags & EARLY_FLAKE_DETECTION_ENABLED_FLAG) != 0;
    boolean earlyFlakeDetectionFaulty = (flags & EARLY_FLAKE_DETECTION_FAULTY_FLAG) != 0;

    long testsSkippedTotal = Serializer.readLong(buffer);
    Collection<TestFramework> testFrameworks =
        Serializer.readList(buffer, TestFramework::deserialize);
    byte[] coverageData = Serializer.readByteArray(buffer);

    return new ModuleExecutionResult(
        sessionId,
        moduleId,
        coverageEnabled,
        itrEnabled,
        earlyFlakeDetectionEnabled,
        earlyFlakeDetectionFaulty,
        testsSkippedTotal,
        testFrameworks,
        coverageData);
  }
}
