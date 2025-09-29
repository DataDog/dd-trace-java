package datadog.trace.civisibility.ipc;

import datadog.trace.api.DDTraceId;
import datadog.trace.civisibility.ipc.serialization.Serializer;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Objects;

public class ModuleExecutionResult extends ModuleSignal {

  private static final int COVERAGE_ENABLED_FLAG = 1;
  private static final int TEST_SKIPPING_ENABLED_FLAG = 2;
  private static final int EARLY_FLAKE_DETECTION_ENABLED_FLAG = 4;
  private static final int EARLY_FLAKE_DETECTION_FAULTY_FLAG = 8;
  private static final int TEST_MANAGEMENT_ENABLED_FLAG = 16;
  private static final int HAS_FAILED_TEST_REPLAY_TESTS_FLAG = 32;

  private final boolean coverageEnabled;
  private final boolean testSkippingEnabled;
  private final boolean earlyFlakeDetectionEnabled;
  private final boolean earlyFlakeDetectionFaulty;
  private final boolean testManagementEnabled;
  private final boolean hasFailedTestReplayTests;
  private final long testsSkippedTotal;
  private final Collection<TestFramework> testFrameworks;

  public ModuleExecutionResult(
      DDTraceId sessionId,
      long moduleId,
      boolean coverageEnabled,
      boolean testSkippingEnabled,
      boolean earlyFlakeDetectionEnabled,
      boolean earlyFlakeDetectionFaulty,
      boolean testManagementEnabled,
      boolean hasFailedTestReplayTests,
      long testsSkippedTotal,
      Collection<TestFramework> testFrameworks) {
    super(sessionId, moduleId);
    this.coverageEnabled = coverageEnabled;
    this.testSkippingEnabled = testSkippingEnabled;
    this.earlyFlakeDetectionEnabled = earlyFlakeDetectionEnabled;
    this.earlyFlakeDetectionFaulty = earlyFlakeDetectionFaulty;
    this.testManagementEnabled = testManagementEnabled;
    this.hasFailedTestReplayTests = hasFailedTestReplayTests;
    this.testsSkippedTotal = testsSkippedTotal;
    this.testFrameworks = testFrameworks;
  }

  public boolean isCoverageEnabled() {
    return coverageEnabled;
  }

  public boolean isTestSkippingEnabled() {
    return testSkippingEnabled;
  }

  public boolean isEarlyFlakeDetectionEnabled() {
    return earlyFlakeDetectionEnabled;
  }

  public boolean isEarlyFlakeDetectionFaulty() {
    return earlyFlakeDetectionFaulty;
  }

  public boolean isTestManagementEnabled() {
    return testManagementEnabled;
  }

  public boolean hasFailedTestReplayTests() {
    return hasFailedTestReplayTests;
  }

  public long getTestsSkippedTotal() {
    return testsSkippedTotal;
  }

  public Collection<TestFramework> getTestFrameworks() {
    return testFrameworks;
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
    return sessionId.toLong() == that.sessionId.toLong()
        && sessionId.toHighOrderLong() == that.sessionId.toHighOrderLong()
        && moduleId == that.moduleId
        && coverageEnabled == that.coverageEnabled
        && testSkippingEnabled == that.testSkippingEnabled
        && hasFailedTestReplayTests == that.hasFailedTestReplayTests
        && testsSkippedTotal == that.testsSkippedTotal
        && Objects.equals(testFrameworks, that.testFrameworks);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        sessionId,
        moduleId,
        coverageEnabled,
        testSkippingEnabled,
        hasFailedTestReplayTests,
        testsSkippedTotal,
        testFrameworks);
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
        + ", hasFailedTestReplayTests="
        + hasFailedTestReplayTests
        + ", testSkippingEnabled="
        + testSkippingEnabled
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
    s.write(sessionId.toHexString());
    s.write(moduleId);

    byte flags = 0;
    if (coverageEnabled) {
      flags |= COVERAGE_ENABLED_FLAG;
    }
    if (testSkippingEnabled) {
      flags |= TEST_SKIPPING_ENABLED_FLAG;
    }
    if (earlyFlakeDetectionEnabled) {
      flags |= EARLY_FLAKE_DETECTION_ENABLED_FLAG;
    }
    if (earlyFlakeDetectionFaulty) {
      flags |= EARLY_FLAKE_DETECTION_FAULTY_FLAG;
    }
    if (testManagementEnabled) {
      flags |= TEST_MANAGEMENT_ENABLED_FLAG;
    }
    if (hasFailedTestReplayTests) {
      flags |= HAS_FAILED_TEST_REPLAY_TESTS_FLAG;
    }
    s.write(flags);

    s.write(testsSkippedTotal);
    s.write(testFrameworks, TestFramework::serialize);

    return s.flush();
  }

  public static ModuleExecutionResult deserialize(ByteBuffer buffer) {
    DDTraceId sessionId = DDTraceId.fromHex(Serializer.readString(buffer));
    long moduleId = Serializer.readLong(buffer);

    int flags = Serializer.readByte(buffer);
    boolean coverageEnabled = (flags & COVERAGE_ENABLED_FLAG) != 0;
    boolean testSkippingEnabled = (flags & TEST_SKIPPING_ENABLED_FLAG) != 0;
    boolean earlyFlakeDetectionEnabled = (flags & EARLY_FLAKE_DETECTION_ENABLED_FLAG) != 0;
    boolean earlyFlakeDetectionFaulty = (flags & EARLY_FLAKE_DETECTION_FAULTY_FLAG) != 0;
    boolean testManagementEnabled = (flags & TEST_MANAGEMENT_ENABLED_FLAG) != 0;
    boolean hasFailedTestReplayTests = (flags & HAS_FAILED_TEST_REPLAY_TESTS_FLAG) != 0;

    long testsSkippedTotal = Serializer.readLong(buffer);
    Collection<TestFramework> testFrameworks =
        Serializer.readList(buffer, TestFramework::deserialize);

    return new ModuleExecutionResult(
        sessionId,
        moduleId,
        coverageEnabled,
        testSkippingEnabled,
        earlyFlakeDetectionEnabled,
        earlyFlakeDetectionFaulty,
        testManagementEnabled,
        hasFailedTestReplayTests,
        testsSkippedTotal,
        testFrameworks);
  }
}
