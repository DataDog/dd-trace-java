import java.util.Map;
import org.apache.kafka.common.serialization.Serializer;

class AvroMockSerializer implements Serializer<AvroMock> {
  @Override
  public void configure(Map configs, boolean isKey) {}

  @Override
  public byte[] serialize(String topic, AvroMock data) {
    return new byte[0];
  }

  @Override
  public void close() {}
}
