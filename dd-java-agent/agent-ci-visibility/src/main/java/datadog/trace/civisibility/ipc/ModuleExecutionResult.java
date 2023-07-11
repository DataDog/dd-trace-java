package datadog.trace.civisibility.ipc;

import java.nio.ByteBuffer;
import java.util.Objects;

public class ModuleExecutionResult implements Signal {

  private static final int SERIALIZED_LENGTH = Long.BYTES + Long.BYTES + 1;
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
  public SignalType getType() {
    return SignalType.MODULE_EXECUTION_RESULT;
  }

  @Override
  public ByteBuffer serialize() {
    ByteBuffer buffer = ByteBuffer.allocate(SERIALIZED_LENGTH);
    buffer.putLong(sessionId);
    buffer.putLong(moduleId);

    byte flags = 0;
    if (coverageEnabled) {
      flags |= COVERAGE_ENABLED_FLAG;
    }
    if (itrEnabled) {
      flags |= ITR_ENABLED_FLAG;
    }
    if (itrTestsSkipped) {
      flags |= ITR_TESTS_SKIPPED_FLAG;
    }
    buffer.put(flags);
    buffer.flip();
    return buffer;
  }

  public static ModuleExecutionResult deserialize(ByteBuffer buffer) {
    if (buffer.remaining() != SERIALIZED_LENGTH) {
      throw new IllegalArgumentException(
          "Expected " + SERIALIZED_LENGTH + " bytes, got " + buffer.remaining() + ", " + buffer);
    }
    long sessionId = buffer.getLong();
    long moduleId = buffer.getLong();
    int flags = buffer.get();
    boolean coverageEnabled = (flags & COVERAGE_ENABLED_FLAG) != 0;
    boolean itrEnabled = (flags & ITR_ENABLED_FLAG) != 0;
    boolean itrTestsSkipped = (flags & ITR_TESTS_SKIPPED_FLAG) != 0;
    return new ModuleExecutionResult(
        sessionId, moduleId, coverageEnabled, itrEnabled, itrTestsSkipped);
  }
}
