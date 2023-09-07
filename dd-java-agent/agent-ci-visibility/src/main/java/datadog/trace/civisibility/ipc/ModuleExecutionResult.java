package datadog.trace.civisibility.ipc;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;
import javax.annotation.Nullable;

public class ModuleExecutionResult implements Signal {

  private static final int FIXED_LENGTH = Long.BYTES + Long.BYTES + Long.BYTES + 1;
  private static final int COVERAGE_ENABLED_FLAG = 1;
  private static final int ITR_ENABLED_FLAG = 2;

  private final long sessionId;
  private final long moduleId;
  private final boolean coverageEnabled;
  private final boolean itrEnabled;
  private final long testsSkippedTotal;
  @Nullable private final String testFramework;
  @Nullable private final String testFrameworkVersion;
  @Nullable private final byte[] coverageData;

  public ModuleExecutionResult(
      long sessionId,
      long moduleId,
      boolean coverageEnabled,
      boolean itrEnabled,
      long testsSkippedTotal,
      @Nullable String testFramework,
      @Nullable String testFrameworkVersion,
      @Nullable byte[] coverageData) {
    this.sessionId = sessionId;
    this.moduleId = moduleId;
    this.coverageEnabled = coverageEnabled;
    this.itrEnabled = itrEnabled;
    this.testsSkippedTotal = testsSkippedTotal;
    this.testFramework = testFramework;
    this.testFrameworkVersion = testFrameworkVersion;
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

  public long getTestsSkippedTotal() {
    return testsSkippedTotal;
  }

  @Nullable
  public String getTestFramework() {
    return testFramework;
  }

  @Nullable
  public String getTestFrameworkVersion() {
    return testFrameworkVersion;
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
        && Objects.equals(testFramework, that.testFramework)
        && Objects.equals(testFrameworkVersion, that.testFrameworkVersion)
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
        testFramework,
        testFrameworkVersion,
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
    byte[] testFrameworkBytes = testFramework != null ? testFramework.getBytes() : null;
    byte[] testFrameworkVersionBytes =
        testFrameworkVersion != null ? testFrameworkVersion.getBytes() : null;

    int testFrameworkLength = testFrameworkBytes != null ? testFrameworkBytes.length : 0;
    int testFrameworkVersionLength =
        testFrameworkVersionBytes != null ? testFrameworkVersionBytes.length : 0;
    int coverageDataLength = coverageData != null ? coverageData.length : 0;
    int variableLength =
        Integer.BYTES * 3 + testFrameworkLength + testFrameworkVersionLength + coverageDataLength;

    ByteBuffer buffer = ByteBuffer.allocate(FIXED_LENGTH + variableLength);
    buffer.putLong(sessionId);
    buffer.putLong(moduleId);
    buffer.putLong(testsSkippedTotal);

    byte flags = 0;
    if (coverageEnabled) {
      flags |= COVERAGE_ENABLED_FLAG;
    }
    if (itrEnabled) {
      flags |= ITR_ENABLED_FLAG;
    }
    buffer.put(flags);

    buffer.putInt(testFrameworkLength);
    if (testFrameworkLength != 0) {
      buffer.put(testFrameworkBytes);
    }

    buffer.putInt(testFrameworkVersionLength);
    if (testFrameworkVersionLength != 0) {
      buffer.put(testFrameworkVersionBytes);
    }

    buffer.putInt(coverageDataLength);
    if (coverageDataLength != 0) {
      buffer.put(coverageData);
    }

    buffer.flip();
    return buffer;
  }

  public static ModuleExecutionResult deserialize(ByteBuffer buffer) {
    if (buffer.remaining() < FIXED_LENGTH) {
      throw new IllegalArgumentException(
          "Expected at least "
              + FIXED_LENGTH
              + " bytes, got "
              + buffer.remaining()
              + ", "
              + buffer);
    }
    long sessionId = buffer.getLong();
    long moduleId = buffer.getLong();
    long testsSkippedTotal = buffer.getLong();
    int flags = buffer.get();
    boolean coverageEnabled = (flags & COVERAGE_ENABLED_FLAG) != 0;
    boolean itrEnabled = (flags & ITR_ENABLED_FLAG) != 0;

    String testFramework;
    int testFrameworkLength = buffer.getInt();
    if (testFrameworkLength != 0) {
      byte[] testFrameworkBytes = new byte[testFrameworkLength];
      buffer.get(testFrameworkBytes);
      testFramework = new String(testFrameworkBytes);
    } else {
      testFramework = null;
    }

    String testFrameworkVersion;
    int testFrameworkVersionLength = buffer.getInt();
    if (testFrameworkVersionLength != 0) {
      byte[] testFrameworkVersionBytes = new byte[testFrameworkVersionLength];
      buffer.get(testFrameworkVersionBytes);
      testFrameworkVersion = new String(testFrameworkVersionBytes);
    } else {
      testFrameworkVersion = null;
    }

    byte[] coverageData;
    int coverageDataLength = buffer.getInt();
    if (coverageDataLength != 0) {
      coverageData = new byte[coverageDataLength];
      buffer.get(coverageData);
    } else {
      coverageData = null;
    }

    return new ModuleExecutionResult(
        sessionId,
        moduleId,
        coverageEnabled,
        itrEnabled,
        testsSkippedTotal,
        testFramework,
        testFrameworkVersion,
        coverageData);
  }
}
