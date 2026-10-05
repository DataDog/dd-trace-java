package datadog.trace.bootstrap.instrumentation.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

/**
 * Covers the behaviour this branch adds to the {@code _datadog} message attribute parser shared by
 * the AWS messaging instrumentations (SQS, SNS, EventBridge, Step Functions): the {@code
 * _dd.p.llmobs_*} propagation tags are forwarded to the extractor out of {@code x-datadog-tags},
 * and every other {@code _dd.p.*} tag stays dropped as it is on master.
 */
class DatadogAttributeParserTest {

  private static final String TRACE_CONTEXT =
      "\"x-datadog-trace-id\":\"1234567890\","
          + "\"x-datadog-parent-id\":\"9876543210\","
          + "\"x-datadog-sampling-priority\":\"1\"";

  private static Map<String, String> parse(String json) {
    Map<String, String> collected = new LinkedHashMap<>();
    DatadogAttributeParser.forEachProperty(
        (key, value) -> {
          collected.put(key, value);
          return true;
        },
        json);
    return collected;
  }

  private static Map<String, String> parseWithTags(String tags) {
    return parse("{" + TRACE_CONTEXT + ",\"x-datadog-tags\":\"" + tags + "\"}");
  }

  /**
   * The LLMObs tags are kept wherever they sit in the header, and the tags other products key off
   * are not — forwarding {@code _dd.p.ts} would change ASM consumer sampling and {@code _dd.p.tid}
   * would change the {@code dd.trace_id} format in logs, neither of which belongs in this change.
   */
  @TableTest({
    "scenario          | tags                                                                   | expected                                   ",
    "llmobs only       | '_dd.p.llmobs_sid=sess-1'                                              | '_dd.p.llmobs_sid=sess-1'                  ",
    "llmobs first      | '_dd.p.llmobs_sid=sess-1,_dd.p.dm=-1'                                  | '_dd.p.llmobs_sid=sess-1'                  ",
    "llmobs last       | '_dd.p.dm=-1,_dd.p.llmobs_sid=sess-1'                                  | '_dd.p.llmobs_sid=sess-1'                  ",
    "llmobs in between | '_dd.p.dm=-1,_dd.p.llmobs_sid=sess-1,_dd.p.tid=6aa01c5400000000'       | '_dd.p.llmobs_sid=sess-1'                  ",
    "several llmobs    | '_dd.p.llmobs_ml_app=app,_dd.p.tid=6aa01c5400000000,_dd.p.llmobs_sr=1' | '_dd.p.llmobs_ml_app=app,_dd.p.llmobs_sr=1'",
    "trace source      | '_dd.p.ts=02,_dd.p.llmobs_sid=sess-1'                                  | '_dd.p.llmobs_sid=sess-1'                  ",
    "empty value       | '_dd.p.llmobs_sid=,_dd.p.dm=-1'                                        | '_dd.p.llmobs_sid='                        "
  })
  void forwardsOnlyTheLlmObsPropagationTags(String tags, String expected) {
    assertEquals(expected, parseWithTags(tags).get("x-datadog-tags"));
  }

  /**
   * A header with nothing to forward is not forwarded at all, rather than forwarded empty — an
   * empty {@code x-datadog-tags} is not the same thing to the extractor as an absent one.
   */
  @TableTest({
    "scenario                  | tags                                    ",
    "no llmobs tags            | '_dd.p.dm=-1,_dd.p.tid=6aa01c5400000000'",
    "empty header              | ''                                      ",
    "prefix only in a value    | '_dd.p.dm=_dd.p.llmobs_sid'             ",
    "prefix is not a whole key | '_dd.p.llmobsx=1'                       "
  })
  void doesNotForwardAHeaderWithNoLlmObsTags(String tags) {
    Map<String, String> collected = parseWithTags(tags);
    assertFalse(collected.containsKey("x-datadog-tags"), () -> "forwarded " + collected);
  }

  @Test
  void forwardsNothingWhenTheHeaderIsAbsent() {
    Map<String, String> collected = parse("{" + TRACE_CONTEXT + "}");
    assertEquals("1234567890", collected.get("x-datadog-trace-id"));
    assertNull(collected.get("x-datadog-tags"));
  }

  /**
   * A property is located by its quoted name, not by a bare substring search, so a value that
   * happens to contain another property's name does not shadow the real one. This matters now that
   * {@code x-datadog-tags} carries application-supplied values — an ML app can be named anything.
   */
  @Test
  void readsAPropertyWhoseNameAlsoAppearsInsideAnEarlierValue() {
    Map<String, String> collected =
        parse(
            "{\"x-datadog-trace-id\":\"1234567890\","
                + "\"x-datadog-tags\":\"_dd.p.llmobs_ml_app=x-datadog-sampling-priority:9\","
                + "\"x-datadog-parent-id\":\"9876543210\","
                + "\"x-datadog-sampling-priority\":\"1\"}");
    assertEquals("1", collected.get("x-datadog-sampling-priority"));
    assertEquals(
        "_dd.p.llmobs_ml_app=x-datadog-sampling-priority:9", collected.get("x-datadog-tags"));
  }

  /** The tags are only read once a trace id has been found, which is what gates the whole block. */
  @Test
  void forwardsNothingWhenThereIsNoTraceId() {
    assertEquals(
        0,
        parse("{\"x-datadog-tags\":\"_dd.p.llmobs_sid=sess-1\"}").size(),
        "tags should not be read without a trace id");
  }
}
