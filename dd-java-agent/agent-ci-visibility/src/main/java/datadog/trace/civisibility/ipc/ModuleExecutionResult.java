package datadog.trace.civisibility.ipc;

import java.util.Arrays;
import java.util.Objects;

public class ModuleExecutionResult implements Signal {

  private static final int SERIALIZED_LENGTH = 1 + Long.BYTES + Long.BYTES + 1;
  private static final int FLAGS_IDX = 1 + Long.BYTES + Long.BYTES;
  private static final int COVERAGE_ENABLED_FLAG = 1;
  private static final int ITR_ENABLED_FLAG = 2;
  private static final int ITR_TESTS_SKIPPED_FLAG = 4;

  private final long sessionId;
  private final long moduleId;
  private final boolean coverageEnabled;
  private final boolean itrEnabled;
  private final boolean itrTestsSkipped;

  public ModuleExecutionResult(
      long sessionId,
      long moduleId,
      boolean coverageEnabled,
      boolean itrEnabled,
      boolean itrTestsSkipped) {
    this.sessionId = sessionId;
    this.moduleId = moduleId;
    this.coverageEnabled = coverageEnabled;
    this.itrEnabled = itrEnabled;
    this.itrTestsSkipped = itrTestsSkipped;
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

  public boolean isItrTestsSkipped() {
    return itrTestsSkipped;
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
        && itrTestsSkipped == that.itrTestsSkipped;
  }

  @Override
  public int hashCode() {
    return Objects.hash(sessionId, moduleId, coverageEnabled, itrEnabled, itrEnabled);
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
        + itrTestsSkipped
        + '}';
  }

  @Override
  public byte[] serialize() {
    byte[] bytes = new byte[SERIALIZED_LENGTH];
    bytes[0] = SignalType.MODULE_EXECUTION_RESULT.getCode();
    ByteUtils.putLong(bytes, 1, sessionId);
    ByteUtils.putLong(bytes, 1 + Long.BYTES, moduleId);

    if (coverageEnabled) {
      bytes[FLAGS_IDX] |= COVERAGE_ENABLED_FLAG;
    }
    if (itrEnabled) {
      bytes[FLAGS_IDX] |= ITR_ENABLED_FLAG;
    }
    if (itrTestsSkipped) {
      bytes[FLAGS_IDX] |= ITR_TESTS_SKIPPED_FLAG;
    }
    return bytes;
  }

  public static ModuleExecutionResult deserialize(byte[] bytes) {
    int expectedLength = SERIALIZED_LENGTH;
    if (bytes.length != expectedLength) {
      throw new IllegalArgumentException(
          "Expected "
              + expectedLength
              + " bytes, got "
              + bytes.length
              + ", "
              + Arrays.toString(bytes));
    }
    long sessionId = ByteUtils.getLong(bytes, 1);
    long moduleId = ByteUtils.getLong(bytes, 1 + Long.BYTES);

    int flags = bytes[FLAGS_IDX];
    boolean coverageEnabled = (flags & COVERAGE_ENABLED_FLAG) != 0;
    boolean itrEnabled = (flags & ITR_ENABLED_FLAG) != 0;
    boolean itrTestsSkipped = (flags & ITR_TESTS_SKIPPED_FLAG) != 0;
    return new ModuleExecutionResult(
        sessionId, moduleId, coverageEnabled, itrEnabled, itrTestsSkipped);
  }
}
