package datadog.trace.civisibility.ipc;

import datadog.trace.api.civisibility.config.SkippableTest;
import datadog.trace.civisibility.config.SkippableTestsSerializer;
import java.nio.ByteBuffer;
import java.util.Collection;

public class SkippableTestsResponse implements SignalResponse {

  private final Collection<SkippableTest> tests;

  public SkippableTestsResponse(Collection<SkippableTest> tests) {
    this.tests = tests;
  }

  public Collection<SkippableTest> getTests() {
    return tests;
  }

  @Override
  public SignalType getType() {
    return SignalType.SKIPPABLE_TESTS_RESPONSE;
  }

  @Override
  public ByteBuffer serialize() {
    return SkippableTestsSerializer.serialize(tests);
  }

  public static SkippableTestsResponse deserialize(ByteBuffer buffer) {
    Collection<SkippableTest> tests = SkippableTestsSerializer.deserialize(buffer);
    return new SkippableTestsResponse(tests);
  }
}
