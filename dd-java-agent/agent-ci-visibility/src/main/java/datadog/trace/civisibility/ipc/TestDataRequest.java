package datadog.trace.civisibility.ipc;

import datadog.trace.civisibility.config.JvmInfo;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public class TestDataRequest implements Signal {

  private final TestDataType testDataType;
  private final String relativeModulePath;
  private final JvmInfo jvmInfo;

  public TestDataRequest(TestDataType testDataType, String relativeModulePath, JvmInfo jvmInfo) {
    this.testDataType = testDataType;
    this.relativeModulePath = relativeModulePath;
    this.jvmInfo = jvmInfo;
  }

  @Override
  public SignalType getType() {
    return SignalType.TEST_DATA_REQUEST;
  }

  public TestDataType getTestDataType() {
    return testDataType;
  }

  public String getRelativeModulePath() {
    return relativeModulePath;
  }

  public JvmInfo getJvmInfo() {
    return jvmInfo;
  }

  @Override
  public String toString() {
    return "SkippableTestsRequest{"
        + "testDataType="
        + testDataType
        + ",relativeModulePath="
        + relativeModulePath
        + ", jvmInfo="
        + jvmInfo
        + '}';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    TestDataRequest that = (TestDataRequest) o;
    return testDataType == that.testDataType
        && Objects.equals(relativeModulePath, that.relativeModulePath)
        && Objects.equals(jvmInfo, that.jvmInfo);
  }

  @Override
  public int hashCode() {
    return Objects.hash(testDataType, relativeModulePath, jvmInfo);
  }

  @Override
  public ByteBuffer serialize() {

    byte[] modulePathBytes =
        relativeModulePath != null ? relativeModulePath.getBytes(StandardCharsets.UTF_8) : null;

    String jvmName = jvmInfo.getName();
    byte[] jvmNameBytes = jvmName != null ? jvmName.getBytes(StandardCharsets.UTF_8) : null;

    String jvmVersion = jvmInfo.getVersion();
    byte[] jvmVersionBytes =
        jvmVersion != null ? jvmVersion.getBytes(StandardCharsets.UTF_8) : null;

    String jvmVendor = jvmInfo.getVendor();
    byte[] jvmVendorBytes = jvmVendor != null ? jvmVendor.getBytes(StandardCharsets.UTF_8) : null;

    ByteBuffer buffer =
        ByteBuffer.allocate(
            1 // test data type ordinal
                + serializeStringLength(modulePathBytes)
                + serializeStringLength(jvmNameBytes)
                + serializeStringLength(jvmVersionBytes)
                + serializeStringLength(jvmVendorBytes));

    buffer.put((byte) testDataType.ordinal());
    serializeString(buffer, modulePathBytes);
    serializeString(buffer, jvmNameBytes);
    serializeString(buffer, jvmVersionBytes);
    serializeString(buffer, jvmVendorBytes);

    buffer.flip();
    return buffer;
  }

  private static int serializeStringLength(byte[] stringBytes) {
    return Integer.BYTES + (stringBytes != null ? stringBytes.length : 0);
  }

  private static void serializeString(ByteBuffer buffer, byte[] stringBytes) {
    if (stringBytes != null) {
      buffer.putInt(stringBytes.length);
      buffer.put(stringBytes);
    } else {
      buffer.putInt(-1);
    }
  }

  public static TestDataRequest deserialize(ByteBuffer buffer) {
    TestDataType testDataType = TestDataType.byOrdinal(buffer.get());
    String relativeModulePath = deserializeString(buffer);
    String jvmName = deserializeString(buffer);
    String jvmVersion = deserializeString(buffer);
    String jvmVendor = deserializeString(buffer);
    JvmInfo jvmInfo = new JvmInfo(jvmName, jvmVersion, jvmVendor);
    return new TestDataRequest(testDataType, relativeModulePath, jvmInfo);
  }

  private static String deserializeString(ByteBuffer buffer) {
    int jvmNameBytesLength = buffer.getInt();
    if (jvmNameBytesLength >= 0) {
      byte[] jvmNameBytes = new byte[jvmNameBytesLength];
      buffer.get(jvmNameBytes);
      return new String(jvmNameBytes, StandardCharsets.UTF_8);
    } else {
      return null;
    }
  }
}
