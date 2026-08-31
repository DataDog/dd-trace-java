package datadog.trace.core.propagation;

import static datadog.trace.api.config.TracerConfig.TRACE_BAGGAGE_MAX_BYTES;
import static datadog.trace.api.config.TracerConfig.TRACE_BAGGAGE_MAX_ITEMS;
import static datadog.trace.bootstrap.instrumentation.api.ContextVisitors.stringValuesMap;
import static datadog.trace.core.propagation.HttpCodecTestHelper.baggageItems;
import static datadog.trace.core.propagation.HttpCodecTestHelper.generateBaggageItems;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import org.junit.jupiter.api.Test;

/**
 * Behavior contract for the baggage limits enforced by {@link ContextInterpreter#addBaggageItem}.
 * The limits are applied after the propagation style has parsed the key and value, so they are
 * style independent: every codec carrying caller baggage runs this suite as a {@code @Nested} class
 * of its extractor test, supplying only the wire format.
 */
abstract class AbstractOTBaggageTest {

  /** Returns the extractor under test, built by the enclosing extractor test. */
  protected abstract HttpCodec.Extractor extractor();

  /**
   * Returns the given baggage items, in order, in this style's wire format. A repeated key must be
   * spelled so that the style reads it back as the same baggage key.
   */
  protected abstract Map<String, String> baggageHeaders(List<Entry<String, String>> items);

  /** Returns the headers carrying {@code itemCount} generated baggage items. */
  protected final Map<String, String> generateBaggageHeaders(int itemCount) {
    return baggageHeaders(generateBaggageItems(itemCount));
  }

  /**
   * Extracts the given headers and returns the baggage of the resulting context. Headers carrying
   * nothing but baggage create no context at all once every item is dropped, which is reported as
   * empty baggage rather than as a missing context.
   */
  protected final Map<String, String> extractBaggage(Map<String, String> headers) {
    TagContext context = this.extractor().extract(headers, stringValuesMap());
    return context == null ? emptyMap() : context.getBaggage();
  }

  @Test
  @WithConfig(key = TRACE_BAGGAGE_MAX_ITEMS, value = "3")
  void stopsAtItemLimit() {
    assertEquals(3, extractBaggage(generateBaggageHeaders(50)).size());
  }

  @Test
  @WithConfig(key = TRACE_BAGGAGE_MAX_BYTES, value = "24")
  void stopsAtByteLimit() {
    // with single digit indices each stored item is "keyN" + "valueN" = 10 bytes, so 2 fit in 24
    // bytes and a third would take the total to 30
    assertEquals(2, extractBaggage(generateBaggageHeaders(10)).size());
  }

  @Test
  @WithConfig(key = TRACE_BAGGAGE_MAX_BYTES, value = "24")
  @WithConfig(key = TRACE_BAGGAGE_MAX_ITEMS, value = "2")
  void chargesRepeatedKeyOnce() {
    Map<String, String> baggage =
        extractBaggage(
            baggageHeaders(baggageItems("key0", "val0", "key0", "val0", "a", "0123456789")));

    Map<String, String> expected = new HashMap<>();
    expected.put("key0", "val0");
    expected.put("a", "0123456789");
    assertEquals(expected, baggage);
  }

  @Test
  @WithConfig(key = TRACE_BAGGAGE_MAX_BYTES, value = "8")
  void chargesEncodedValueSize() {
    // the budget is charged before decoding, so the 9 character raw value does not fit
    Map<String, String> baggage =
        extractBaggage(baggageHeaders(baggageItems("a", "b", "c", "%E2%99%A5")));

    assertEquals(singletonMap("a", "b"), baggage);
  }

  @Test
  @WithConfig(key = TRACE_BAGGAGE_MAX_BYTES, value = "3")
  void chargesLiteralUtf8ByCharacterCount() {
    // "a" + "♥" is 2 characters, so the 2 characters of "b" + "c" no longer fit
    Map<String, String> baggage = extractBaggage(baggageHeaders(baggageItems("a", "♥", "b", "c")));

    assertEquals(singletonMap("a", "♥"), baggage);
  }

  @Test
  @WithConfig(key = TRACE_BAGGAGE_MAX_ITEMS, value = "0")
  void dropsAllBaggageWhenItemLimitIsZero() {
    assertTrue(extractBaggage(generateBaggageHeaders(10)).isEmpty());
  }

  @Test
  @WithConfig(key = TRACE_BAGGAGE_MAX_BYTES, value = "0")
  void dropsAllBaggageWhenByteLimitIsZero() {
    assertTrue(extractBaggage(generateBaggageHeaders(10)).isEmpty());
  }
}
