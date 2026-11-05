package datadog.trace.lambda;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.Config;
import datadog.trace.api.DDTraceId;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanLink;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDCoreJavaSpecification;
import datadog.trace.core.DDSpan;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class LambdaSpanPointersTest extends DDCoreJavaSpecification {
  @TableTest({
    "scenario  | key           | etag       | hash                            ",
    "plain     | some-key.data | ab12ef34   | e721375466d4116ab551213fdea08413",
    "unicode   | some-key.你好 | ab12ef34   | d1333a04b9928ab462b5c6cadfa401f4",
    "multipart | some-key.data | ab12ef34-5 | 2b90dffc37ebc7bc610152c3dc72af9f"
  })
  void s3ReferenceHashes(String key, String etag, String hash) {
    CoreTracer tracer = tracerBuilder().build();
    try {
      DDSpan span = (DDSpan) tracer.buildSpan("test", "root").start();
      String record =
          "{\"s3\":{\"bucket\":{\"name\":\"some-bucket\"},\"object\":{\"key\":\""
              + key
              + "\",\"eTag\":\"\\\""
              + etag
              + "\\\"\"}}}";
      // The second record must not introduce another pointer.
      LambdaSpanPointers.attach(
          Config.get(),
          span,
          LambdaEventData.json("{\"Records\":[" + record + "," + record + "]}"));
      assertPointer(span, "aws.s3.object", hash);
      span.finish();
    } finally {
      tracer.close();
    }
  }

  @TableTest({
    "scenario          | keys                                                                    | hash                            ",
    "string            | '{\"id\":{\"S\":\"abc123\"}}'                                           | 69706c9e1e41a2f0cf8c0650f91cb0c2",
    "binary and number | '{\"num_key\":{\"N\":\"42\"},\"bin_key\":{\"B\":\"SGVsbG8gV29ybGQ=\"}}' | 2031d2d69b45adc3d5c27691924ddfcc",
    "reversed          | '{\"bin_key\":{\"B\":\"SGVsbG8gV29ybGQ=\"},\"num_key\":{\"N\":\"42\"}}' | 2031d2d69b45adc3d5c27691924ddfcc"
  })
  void dynamodbReferenceHashes(String keys, String hash) {
    CoreTracer tracer = tracerBuilder().build();
    try {
      DDSpan span = (DDSpan) tracer.buildSpan("test", "root").start();
      LambdaSpanPointers.attach(Config.get(), span, dynamodb(keys));
      assertPointer(span, "aws.dynamodb.item", hash);
      span.finish();
    } finally {
      tracer.close();
    }
  }

  @TableTest({
    "scenario         | keys                                                           ",
    "missing          | '{}'                                                           ",
    "invalid base64   | '{\"id\":{\"B\":\"!\"}}'                                       ",
    "invalid utf8     | '{\"id\":{\"B\":\"/w==\"}}'                                    ",
    "unsupported type | '{\"id\":{\"BOOL\":true}}'                                     ",
    "too many keys    | '{\"a\":{\"S\":\"1\"},\"b\":{\"S\":\"2\"},\"c\":{\"S\":\"3\"}}'"
  })
  void invalidDynamoKeysDoNotAddLinks(String keys) {
    CoreTracer tracer = tracerBuilder().build();
    try {
      DDSpan span = (DDSpan) tracer.buildSpan("test", "root").start();
      LambdaSpanPointers.attach(Config.get(), span, dynamodb(keys));
      assertTrue(span.getLinks().isEmpty());
      span.finish();
    } finally {
      tracer.close();
    }
  }

  @Test
  void honorsAwsPointerSetting() {
    CoreTracer tracer = tracerBuilder().build();
    try {
      DDSpan span = (DDSpan) tracer.buildSpan("test", "root").start();
      Properties properties = new Properties();
      properties.setProperty("trace.awsadd.span.pointers", "false");
      LambdaSpanPointers.attach(
          Config.get(properties), span, dynamodb("{\"id\":{\"S\":\"abc123\"}}"));
      assertTrue(span.getLinks().isEmpty());
      span.finish();
    } finally {
      tracer.close();
    }
  }

  @Test
  void nativeInvocationAttachesToInferredRootWithoutTraceParent() {
    ListWriter writer = new ListWriter();
    CoreTracer tracer = tracerBuilder().writer(writer).build();
    try {

      AgentTracer.LambdaInvocation invocation =
          LambdaInvocation.start(
              tracer,
              Config.get(),
              dynamodb("{\"id\":{\"S\":\"abc123\"}}"),
              "request",
              "function",
              "1",
              null);
      DDSpan root = (DDSpan) invocation.span().getLocalRootSpan();
      assertEquals("aws.dynamodb", root.getOperationName().toString());
      assertEquals(0, root.getParentId());
      assertPointer(root, "aws.dynamodb.item", "69706c9e1e41a2f0cf8c0650f91cb0c2");
      assertTrue(((DDSpan) invocation.span()).getLinks().isEmpty());
      invocation.onExit(null, null);
      invocation.finish();
      assertEquals(2, writer.get(0).size());
    } finally {
      tracer.close();
    }
  }

  private static Map<String, Object> dynamodb(String keys) {
    return LambdaEventData.json(
        "{\"Records\":[{\"eventSourceARN\":\"arn:aws:dynamodb:us-east-1:123:table/TestTable/stream/date\",\"dynamodb\":{\"Keys\":"
            + keys
            + "}}]}");
  }

  private static void assertPointer(DDSpan span, String kind, String hash) {
    assertEquals(1, span.getLinks().size());
    AgentSpanLink link = span.getLinks().get(0);
    assertEquals(DDTraceId.ZERO, link.traceId());
    assertEquals(0, link.spanId());
    assertEquals(0, link.traceFlags());
    assertEquals("u", link.attributes().asMap().get("ptr.dir"));
    assertEquals(kind, link.attributes().asMap().get("ptr.kind"));
    assertEquals(hash, link.attributes().asMap().get("ptr.hash"));
    assertEquals("span-pointer", link.attributes().asMap().get("link.kind"));
  }
}
