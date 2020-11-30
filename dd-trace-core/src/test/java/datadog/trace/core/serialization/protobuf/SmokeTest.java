package datadog.trace.core.serialization.protobuf;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import datadog.trace.core.serialization.ByteBufferConsumer;
import datadog.trace.core.serialization.Mapper;
import datadog.trace.core.serialization.Writable;
import datadog.trace.core.serialization.WritableFormatter;
import java.nio.ByteBuffer;
import org.junit.Assert;
import org.junit.Test;

public class SmokeTest {

  @Test
  public void writeAndParseTrace() {
    WritableFormatter writer =
        new ProtobufWriter(
            new ByteBufferConsumer() {
              @Override
              public void accept(int messageCount, ByteBuffer buffer) {
                verify(messageCount, buffer);
              }
            },
            ByteBuffer.allocate(1024));
    writer.format(
        null,
        new Mapper<Object>() {
          @Override
          public void map(Object data, Writable writable) {
            writable.startStruct(2); // a message with two fields
            writable.startArray(4);
            writable.writeString("foo", null);
            writable.writeString("bar", null);
            writable.writeString("qux", null);
            writable.writeString("", null);
            writable.startArray(1);
            writable.startStruct(10);
            writable.writeInt(0); // field
            writable.writeInt(1); // field
            writable.writeInt(2); // field
            writable.writeLong(920134781029L); // field
            writable.writeLong(0xEAEAEAEAEAEAEL); // field
            writable.writeLong(0L); // field
            writable.writeInt(1); // field
            writable.startMap(2); // a map of key-value pairs
            writable.writeInt(0);
            writable.writeInt(1);
            writable.writeInt(1);
            writable.writeInt(2);
            writable.startMap(2);
            writable.writeInt(0); // key
            writable.writeDouble(9374917.2938028); // value
            writable.writeInt(1); // key
            writable.writeDouble(0.0000012121244); // value
            writable.writeInt(4);
          }
        });
    writer.flush();
  }

  @Test
  public void testIntExample() {
    WritableFormatter writer =
        new ProtobufWriter(
            new ByteBufferConsumer() {
              @Override
              public void accept(int messageCount, ByteBuffer buffer) {
                verify(messageCount, buffer);
              }
            },
            ByteBuffer.allocate(1024));
    writer.format(
        150,
        new Mapper<Integer>() {
          @Override
          public void map(Integer data, Writable writable) {
            writable.writeInt(data);
          }
        });
    writer.flush();
  }

  private void verify(int messageCount, ByteBuffer buffer) {
    try {
      UnknownFieldSet parsed = UnknownFieldSet.parseFrom(ByteString.copyFrom(buffer));
    } catch (Exception e) {
      Assert.fail(e.getMessage());
    }
  }
}
