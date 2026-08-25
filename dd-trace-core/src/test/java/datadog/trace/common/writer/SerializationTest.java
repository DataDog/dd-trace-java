package datadog.trace.common.writer;

import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import datadog.trace.test.util.DDJavaSpecification;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.msgpack.core.MessageBufferPacker;
import org.msgpack.core.MessagePack;
import org.msgpack.jackson.dataformat.MessagePackFactory;

class SerializationTest extends DDJavaSpecification {

  @Test
  void testJsonMapperSerialization() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    Map<String, String> map = singletonMap("key1", "val1");
    byte[] serializedMap = mapper.writeValueAsBytes(map);
    byte[] serializedList = ("[" + new String(serializedMap) + "]").getBytes();

    List<Map<String, String>> result =
        mapper.readValue(serializedList, new TypeReference<List<Map<String, String>>>() {});

    assertEquals(Collections.singletonList(map), result);
    assertEquals("[{\"key1\":\"val1\"}]", new String(serializedList));
  }

  @Test
  void testMsgpackMapperSerialization() throws Exception {
    // setup
    ObjectMapper mapper = new ObjectMapper(new MessagePackFactory());
    // GStrings get odd results in the serializer.
    List<Map<String, String>> input = new ArrayList<>();
    for (int i = 1; i <= 1; i++) {
      input.add(singletonMap("key" + i, "val" + i));
    }
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
