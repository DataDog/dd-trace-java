package datadog.trace.civisibility.ipc;

import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.civisibility.config.TestIdentifierSerializer;
import java.nio.ByteBuffer;
import java.util.Collection;

public class TestDataResponse implements SignalResponse {

  private final Collection<TestIdentifier> tests;

  public TestDataResponse(Collection<TestIdentifier> tests) {
    this.tests = tests;
  }

  public Collection<TestIdentifier> getTests() {
    return tests;
  }

  @Override
  public SignalType getType() {
    return SignalType.TEST_DATA_RESPONSE;
  }

  @Override
  public ByteBuffer serialize() {
    return TestIdentifierSerializer.serialize(tests);
  }

  public static TestDataResponse deserialize(ByteBuffer buffer) {
    Collection<TestIdentifier> tests = TestIdentifierSerializer.deserialize(buffer);
    return new TestDataResponse(tests);
  }
}
