package datadog.trace.civisibility.ipc;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
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
  private final Collection<TestFramework> testFrameworks;
  @Nullable private final byte[] coverageData;

  public ModuleExecutionResult(
      long sessionId,
      long moduleId,
      boolean coverageEnabled,
      boolean itrEnabled,
      long testsSkippedTotal,
      Collection<TestFramework> testFrameworks,
      @Nullable byte[] coverageData) {
    this.sessionId = sessionId;
    this.moduleId = moduleId;
    this.coverageEnabled = coverageEnabled;
    this.itrEnabled = itrEnabled;
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
    int coverageDataLength = coverageData != null ? coverageData.length : 0;
    int variableLength = Integer.BYTES * 2 + coverageDataLength;

    for (TestFramework testFramework : testFrameworks) {
      String testFrameworkName = testFramework.getName();
      String testFrameworkVersion = testFramework.getVersion();
      int testFrameworkNameBytes =
          testFrameworkName != null ? testFrameworkName.getBytes(StandardCharsets.UTF_8).length : 0;
      int testFrameworkVersionBytes =
          testFrameworkVersion != null
              ? testFrameworkVersion.getBytes(StandardCharsets.UTF_8).length
              : 0;
      variableLength += Integer.BYTES * 2 + testFrameworkNameBytes + testFrameworkVersionBytes;
    }

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

    buffer.putInt(testFrameworks.size());
    for (TestFramework testFramework : testFrameworks) {
      String testFrameworkName = testFramework.getName();
      if (testFrameworkName != null) {
        byte[] testFrameworkNameBytes = testFrameworkName.getBytes(StandardCharsets.UTF_8);
        buffer.putInt(testFrameworkNameBytes.length);
        buffer.put(testFrameworkNameBytes);
      } else {
        buffer.putInt(0);
      }

      String testFrameworkVersion = testFramework.getVersion();
      if (testFrameworkVersion != null) {
        byte[] testFrameworkVersionBytes = testFrameworkVersion.getBytes(StandardCharsets.UTF_8);
        buffer.putInt(testFrameworkVersionBytes.length);
        buffer.put(testFrameworkVersionBytes);
      } else {
        buffer.putInt(0);
      }
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

    int testFrameworksSize = buffer.getInt();
    List<TestFramework> testFrameworks = new ArrayList<>(testFrameworksSize);
    for (int i = 0; i < testFrameworksSize; i++) {
      int testFrameworkNameLength = buffer.getInt();
      String testFrameworkName;
      if (testFrameworkNameLength != 0) {
        byte[] testFrameworkNameBytes = new byte[testFrameworkNameLength];
        buffer.get(testFrameworkNameBytes);
        testFrameworkName = new String(testFrameworkNameBytes, StandardCharsets.UTF_8);
      } else {
        testFrameworkName = null;
      }

      int testFrameworkVersionLength = buffer.getInt();
      String testFrameworkVersion;
      if (testFrameworkVersionLength != 0) {
        byte[] testFrameworkVersionBytes = new byte[testFrameworkVersionLength];
        buffer.get(testFrameworkVersionBytes);
        testFrameworkVersion = new String(testFrameworkVersionBytes, StandardCharsets.UTF_8);
      } else {
        testFrameworkVersion = null;
      }

      testFrameworks.add(new TestFramework(testFrameworkName, testFrameworkVersion));
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
        testFrameworks,
        coverageData);
  }
}
