package datadog.trace.lambda;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.SNSEvent;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import datadog.trace.core.test.DDCoreSpecification;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

abstract class AbstractSerialize {
  public String randomString;
}

class SubClass extends AbstractSerialize {
  SubClass() {
    this.randomString = "tutu";
  }
}

class LambdaRequest {
  public boolean testBool;
  public String emptyStr;
  public Map<String, String> emptyHeaders;
}

interface ApiRequestPath {}

class CustomRequest<P extends ApiRequestPath, B> extends LambdaRequest {
  public P path;
  public B body;
}

class SkipTypeJsonSerializerTest extends DDCoreSpecification {

  static class TestJsonObject {
    public String field1;
    public boolean field2;
    public AbstractSerialize field3;
    public NestedJsonObject field4;
    public ByteArrayInputStream field5;

    TestJsonObject() {
      this.field1 = "toto";
      this.field2 = true;
      this.field3 = new SubClass();
      this.field4 = new NestedJsonObject();
      this.field5 = new ByteArrayInputStream(new byte[0]);
    }
  }

  static class NestedJsonObject {
    public AbstractSerialize field;

    NestedJsonObject() {
      this.field = new SubClass();
    }
  }

  @Test
  void testStringSerialization() {
    JsonAdapter<Object> adapter =
        new Moshi.Builder()
            .add(SkipUnsupportedTypeJsonAdapter.newFactory())
            .build()
            .adapter(Object.class);

    String result = adapter.toJson(new TestJsonObject());
    assertEquals(
        "{\"field1\":\"toto\",\"field2\":true,\"field3\":{},\"field4\":{\"field\":{}},\"field5\":{}}",
        result);
  }

  @Test
  void testSimpleCase() {
    JsonAdapter<Object> adapter =
        new Moshi.Builder()
            .add(SkipUnsupportedTypeJsonAdapter.newFactory())
            .build()
            .adapter(Object.class);

    LinkedHashMap<String, String> list = new LinkedHashMap<>();
    list.put("key0", "item0");
    list.put("key1", "item1");
    list.put("key2", "item2");
    String result = adapter.toJson(list);
    assertEquals("{\"key0\":\"item0\",\"key1\":\"item1\",\"key2\":\"item2\"}", result);
  }

  @Test
  void testSQSEvent() {
    JsonAdapter<Object> adapter =
        new Moshi.Builder()
            .add(SkipUnsupportedTypeJsonAdapter.newFactory())
            .build()
            .adapter(Object.class);

    SQSEvent myEvent = new SQSEvent();
    List<SQSEvent.SQSMessage> records = new ArrayList<>();
    SQSEvent.SQSMessage message = new SQSEvent.SQSMessage();
    message.setMessageId("myId");
    message.setAwsRegion("myRegion");
    records.add(message);
    myEvent.setRecords(records);
    String result = adapter.toJson(myEvent);
    assertEquals("{\"records\":[{\"awsRegion\":\"myRegion\",\"messageId\":\"myId\"}]}", result);
  }

  @Test
  void testSNSEvent() {
    JsonAdapter<Object> adapter =
        new Moshi.Builder()
            .add(SkipUnsupportedTypeJsonAdapter.newFactory())
            .build()
            .adapter(Object.class);

    SNSEvent myEvent = new SNSEvent();
    List<SNSEvent.SNSRecord> records = new ArrayList<>();
    SNSEvent.SNSRecord message = new SNSEvent.SNSRecord();
    message.setEventSource("mySource");
    message.setEventVersion("myVersion");
    records.add(message);
    myEvent.setRecords(records);
    String result = adapter.toJson(myEvent);
    assertEquals(
        "{\"records\":[{\"eventSource\":\"mySource\",\"eventVersion\":\"myVersion\"}]}", result);
  }

  @Test
  void testAPIGatewayProxyRequestEvent() {
    JsonAdapter<Object> adapter =
        new Moshi.Builder()
            .add(SkipUnsupportedTypeJsonAdapter.newFactory())
            .build()
            .adapter(Object.class);

    APIGatewayProxyRequestEvent myEvent = new APIGatewayProxyRequestEvent();
    myEvent.setBody("bababango");
    myEvent.setHttpMethod("POST");
    String result = adapter.toJson(myEvent);
    assertEquals("{\"body\":\"bababango\",\"httpMethod\":\"POST\"}", result);
  }

  @Test
  void testMapStringObjectEvent() {
    JsonAdapter<Object> adapter =
        new Moshi.Builder()
            .add(SkipUnsupportedTypeJsonAdapter.newFactory())
            .build()
            .adapter(Object.class);

    HashMap<String, Object> myEvent = new HashMap<>();
    HashMap<String, Object> myNestedEvent = new HashMap<>();
    myNestedEvent.put("nestedKey0", "nestedValue1");
    myNestedEvent.put("nestedKey1", true);
    myNestedEvent.put("nestedKey2", Arrays.asList("aaa", "bbb", "ccc", "dddd"));
    myEvent.put("firstKey", new TestJsonObject());
    myEvent.put("secondKey", myNestedEvent);
    String result = adapter.toJson(myEvent);
    assertEquals(
        "{\"firstKey\":{\"field1\":\"toto\",\"field2\":true,\"field3\":{},\"field4\":{\"field\":{}},\"field5\":{}},\"secondKey\":{\"nestedKey2\":[\"aaa\",\"bbb\",\"ccc\",\"dddd\"],\"nestedKey0\":\"nestedValue1\",\"nestedKey1\":true}}",
        result);
  }

  @Test
  void testCustomPayload() {
    JsonAdapter<Object> adapter =
        new Moshi.Builder()
            .add(SkipUnsupportedTypeJsonAdapter.newFactory())
            .build()
            .adapter(Object.class);

    CustomRequest customPayload = new CustomRequest();
    String result = adapter.toJson(customPayload);
    assertEquals("{\"body\":{},\"path\":{},\"testBool\":false}", result);
  }
}
