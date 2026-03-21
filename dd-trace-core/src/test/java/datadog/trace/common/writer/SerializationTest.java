package datadog.trace.common.writer;

import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.msgpack.core.MessageBufferPacker;
import org.msgpack.core.MessagePack;
import org.msgpack.jackson.dataformat.MessagePackFactory;

class SerializationTest {

  @Test
  void testJsonMapperSerialization() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    Map<String, String> map = singletonMap("key1", "val1");
    byte[] serializedMap = mapper.writeValueAsBytes(map);
    String serializedListStr = "[" + new String(serializedMap) + "]";
    byte[] serializedList = serializedListStr.getBytes();

    List<Map<String, String>> result =
        mapper.readValue(serializedList, new TypeReference<List<Map<String, String>>>() {});

    List<Map<String, String>> expected = new ArrayList<>();
    expected.add(map);
    assertEquals(expected, result);
    assertEquals("[{\"key1\":\"val1\"}]", new String(serializedList));
  }

  @Test
  void testMsgpackMapperSerialization() throws Exception {
    ObjectMapper mapper = new ObjectMapper(new MessagePackFactory());
    // GStrings get odd results in the serializer.
    List<Map<String, String>> input = new ArrayList<>();
    input.add(singletonMap("key1", "val1"));

    List<byte[]> serializedMaps = new ArrayList<>();
    for (Map<String, String> item : input) {
      serializedMaps.add(mapper.writeValueAsBytes(item));
    }

    MessageBufferPacker packer = MessagePack.newDefaultBufferPacker();
    packer.packArrayHeader(serializedMaps.size());
    for (byte[] bytes : serializedMaps) {
      packer.writePayload(bytes);
    }
    byte[] serializedList = packer.toByteArray();

    List<Map<String, String>> result =
        mapper.readValue(serializedList, new TypeReference<List<Map<String, String>>>() {});

    assertEquals(input, result);
  }
}
