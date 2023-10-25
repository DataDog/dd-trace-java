package datadog.trace.lambda

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent
import com.amazonaws.services.lambda.runtime.events.SNSEvent
import com.amazonaws.services.lambda.runtime.events.SQSEvent
import datadog.trace.core.test.DDCoreSpecification
import com.squareup.moshi.Moshi

abstract class AbstractSerialize {
  public String randomString
}

class SubClass extends AbstractSerialize {
  SubClass() {
    this.randomString = "tutu"
  }
}

class CustomRequest<P extends ApiRequestPath, B> extends LambdaRequest {
  public P path
  public B body
}
interface ApiRequestPath {}
class LambdaRequest {
  public boolean testBool
  public String emptyStr
  public Map<String, String> emptyHeaders
}

class SkipUnhandledTypeJsonSerializerTest extends DDCoreSpecification {

  static class TestJsonObject {

    public String field1
    public boolean field2
    public AbstractSerialize field3
    public NestedJsonObject field4
    public ByteArrayInputStream field5

    TestJsonObject() {
      this.field1 = "toto"
      this.field2 = true
      this.field3 = new SubClass()
      this.field4 = new NestedJsonObject()
      this.field5 = new ByteArrayInputStream()
    }
  }

  static class NestedJsonObject {

    public AbstractSerialize field

    NestedJsonObject() {
      this.field = new SubClass()
    }
  }

  def "test string serialization"() {
    given:
    def adapter = new Moshi.Builder()
      .add(SkipUnsupportedTypeJsonAdapter.newFactory())
      .build()
      .adapter(Object)

    when:
    def result = adapter.toJson(new TestJsonObject())

    then:
    result == "{\"field1\":\"toto\",\"field2\":true,\"field3\":{},\"field4\":{\"field\":{}},\"field5\":{}}"
  }

  def "test simple case"() {
    given:
    def adapter = new Moshi.Builder()
      .add(SkipUnsupportedTypeJsonAdapter.newFactory())
      .build()
      .adapter(Object)

    when:
    def list = new LinkedHashMap<String, String>()
    list.put("key0","item0")
    list.put("key1","item1")
    list.put("key2","item2")
    def result = adapter.toJson(list)

    then:
    result == "{\"key0\":\"item0\",\"key1\":\"item1\",\"key2\":\"item2\"}"
  }

  def "test SQS event "() {
    given:
    def adapter = new Moshi.Builder()
      .add(SkipUnsupportedTypeJsonAdapter.newFactory())
      .build()
      .adapter(Object)

    when:
    def myEvent = new SQSEvent()
    List<SQSEvent.SQSMessage> records = new ArrayList<>()
    SQSEvent.SQSMessage message = new SQSEvent.SQSMessage()
    message.setMessageId("myId")
    message.setAwsRegion("myRegion")
    records.add(message)
    myEvent.setRecords(records)
    def result = adapter.toJson(myEvent)

    then:
    result == "{\"records\":[{\"awsRegion\":\"myRegion\",\"messageId\":\"myId\"}]}"
  }

  def "test SNS Event"() {
    given:
    def adapter = new Moshi.Builder()
      .add(SkipUnsupportedTypeJsonAdapter.newFactory())
      .build()
      .adapter(Object)

    when:
    def myEvent = new SNSEvent()
    List<SNSEvent.SNSRecord> records = new ArrayList<>()
    SNSEvent.SNSRecord message = new SNSEvent.SNSRecord()
    message.setEventSource("mySource")
    message.setEventVersion("myVersion")
    records.add(message)
    myEvent.setRecords(records)
    def result = adapter.toJson(myEvent)

    then:
    result == "{\"records\":[{\"eventSource\":\"mySource\",\"eventVersion\":\"myVersion\"}]}"
  }

  def "test APIGatewayProxyRequest Event"() {
    given:
    def adapter = new Moshi.Builder()
      .add(SkipUnsupportedTypeJsonAdapter.newFactory())
      .build()
      .adapter(Object)

    when:
    def myEvent = new APIGatewayProxyRequestEvent()
    myEvent.setBody("bababango")
    myEvent.setHttpMethod("POST")
    def result = adapter.toJson(myEvent)

    then:
    result == "{\"body\":\"bababango\",\"httpMethod\":\"POST\"}"
  }

  def "test MapStringObject Event"() {
    given:
    def adapter = new Moshi.Builder()
      .add(SkipUnsupportedTypeJsonAdapter.newFactory())
      .build()
      .adapter(Object)

    when:
    def myEvent = new HashMap<String, Object>()
    def myNestedEvent = new HashMap<String, Object>()
    myNestedEvent.put("nestedKey0", "nestedValue1")
    myNestedEvent.put("nestedKey1", true)
    myNestedEvent.put("nestedKey2", ["aaa", "bbb", "ccc", "dddd"])
    myEvent.put("firstKey", new TestJsonObject())
    myEvent.put("secondKey", myNestedEvent)
    def result = adapter.toJson(myEvent)

    then:
    result == "{\"firstKey\":{\"field1\":\"toto\",\"field2\":true,\"field3\":{},\"field4\":{\"field\":{}},\"field5\":{}},\"secondKey\":{\"nestedKey2\":[\"aaa\",\"bbb\",\"ccc\",\"dddd\"],\"nestedKey0\":\"nestedValue1\",\"nestedKey1\":true}}"
  }

  def "test custom payload"() {
    given:
    def adapter = new Moshi.Builder()
      .add(SkipUnsupportedTypeJsonAdapter.newFactory())
      .build()
      .adapter(Object)

    when:
    def customPayload = new CustomRequest()
    def result = adapter.toJson(customPayload)

    then:
    result == "{\"body\":{},\"path\":{},\"testBool\":false}"
  }
}
