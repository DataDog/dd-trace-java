package datadog.trace.core.baggage;

import datadog.context.Context;
import datadog.context.propagation.CarrierSetter;
import datadog.context.propagation.CarrierVisitor;
import datadog.context.propagation.Propagator;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.BaggageContext;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import javax.annotation.ParametersAreNonnullByDefault;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ParametersAreNonnullByDefault
public class BaggagePropagator implements Propagator {
  private static final Logger log = LoggerFactory.getLogger(BaggagePropagator.class);
  static final String BAGGAGE_KEY = "baggage";
  private Config config;
  private final boolean injectBaggage;
  private final boolean extractBaggage;
  private static final Set<Character> UNSAFE_CHARACTERS_KEY =
      new HashSet<>(
          Arrays.asList(
              '"', ',', ';', '\\', '(', ')', '/', ':', '<', '=', '>', '?', '@', '[', ']', '{',
              '}'));
  private static final Set<Character> UNSAFE_CHARACTERS_VALUE =
      new HashSet<>(Arrays.asList('\"', ',', ';', '\\'));

  public BaggagePropagator(Config config) {
    this.injectBaggage = config.isBaggageInject();
    this.extractBaggage = config.isBaggageExtract();
    this.config = config;
  }

  // use primarily for testing purposes
  public BaggagePropagator(boolean injectBaggage, boolean extractBaggage) {
    this.injectBaggage = injectBaggage;
    this.extractBaggage = extractBaggage;
    config = Config.get();
  }

  private int encodeKey(String key, StringBuilder builder) {
    return encode(key, builder, UNSAFE_CHARACTERS_KEY);
  }

  private int encodeValue(String key, StringBuilder builder) {
    return encode(key, builder, UNSAFE_CHARACTERS_VALUE);
  }

  private int encode(String input, StringBuilder builder, Set<Character> UNSAFE_CHARACTERS) {
    int size = 0;
    for (int i = 0; i < input.length(); i++) {
      char c = input.charAt(i);
      if (UNSAFE_CHARACTERS.contains(c) || c > '~' || c <= ' ') { // encode character
        byte[] bytes = Character.toString(c).getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) {
          builder.append('%');
          builder.append(encodeChar((b >> 4) & 0xF));
          builder.append(encodeChar(b & 0xF));
          size++;
        }
      } else {
        builder.append(c);
        size++;
      }
    }
    return size;
  }

  private char encodeChar(int ascii) {
    return (char) (ascii < 10 ? '0' + ascii : 'A' + (ascii - 10));
  }

  @Override
  public <C> void inject(Context context, C carrier, CarrierSetter<C> setter) {
    BaggageContext baggageContext = BaggageContext.fromContext(context);
    if (baggageContext == null) {
      log.debug("BaggageContext instance is missing from the following context {}", context);
      return;
    }

    int maxItems = config.getTraceBaggageMaxItems();
    int maxBytes = config.getTraceBaggageMaxBytes();
    if (!this.injectBaggage || maxItems == 0 || maxBytes == 0) {
      return;
    }

    int processedBaggage = 0;
    int currentBytes = 0;
    StringBuilder baggageText = new StringBuilder();
    for (final Map.Entry<String, String> entry : baggageContext.getBaggage().entrySet()) {
      int byteSize = 1; // default include size of '='
      if (processedBaggage
          != 0) { // if there are already baggage items processed, add and allocate bytes for a
        // comma
        baggageText.append(',');
        byteSize++;
      }

      byteSize += encodeKey(entry.getKey(), baggageText);
      baggageText.append('=');
      byteSize += encodeValue(entry.getValue(), baggageText);

      processedBaggage++;
      if (processedBaggage == maxItems) { // reached the max number of baggage items allowed
        break;
      }
      if (currentBytes + byteSize
          > maxBytes) { // Drop newest k/v pair if adding it leads to exceeding the limit
        baggageText.setLength(currentBytes);
        break;
      }
      currentBytes += byteSize;
    }

    setter.set(carrier, BAGGAGE_KEY, baggageText.toString());
  }

  @Override
  public <C> Context extract(Context context, C carrier, CarrierVisitor<C> visitor) {
    //noinspection ConstantValue
    if (!this.extractBaggage || context == null || carrier == null || visitor == null) {
      return context;
    }
    BaggageContextExtractor baggageContextExtractor = new BaggageContextExtractor();
    visitor.forEachKeyValue(carrier, baggageContextExtractor);
    BaggageContext extractedContext = baggageContextExtractor.extractedContext;
    if (extractedContext == null) {
      return context;
    }
    return extractedContext.storeInto(context);
  }

  public static class BaggageContextExtractor implements BiConsumer<String, String> {
    private BaggageContext extractedContext;

    BaggageContextExtractor() {}

    /** URL decode value */
    private String decode(final String value) {
      String decoded = value;
      try {
        decoded = URLDecoder.decode(value, "UTF-8");
      } catch (final UnsupportedEncodingException | IllegalArgumentException e) {
        log.debug("Failed to decode {}", value);
      }
      return decoded;
    }

    private Map<String, String> parseBaggageHeaders(String input) {
      Map<String, String> baggage = new HashMap<>();
      char keyValueSeparator = '=';
      char pairSeparator = ',';
      int start = 0;

      int pairSeparatorInd = input.indexOf(pairSeparator);
      pairSeparatorInd = pairSeparatorInd == -1 ? input.length() : pairSeparatorInd;
      int kvSeparatorInd = input.indexOf(keyValueSeparator);
      while (kvSeparatorInd != -1) {
        int end = pairSeparatorInd;
        if (kvSeparatorInd > end) {
          log.debug(
              "Dropping baggage headers due to key with no value {}", input.substring(start, end));
          return Collections.emptyMap();
        }
        String key = decode(input.substring(start, kvSeparatorInd).trim());
        String value = decode(input.substring(kvSeparatorInd + 1, end).trim());
        if (key.isEmpty() || value.isEmpty()) {
          log.debug("Dropping baggage headers due to empty k/v {}:{}", key, value);
          return Collections.emptyMap();
        }
        baggage.put(key, value);

        kvSeparatorInd = input.indexOf(keyValueSeparator, pairSeparatorInd + 1);
        pairSeparatorInd = input.indexOf(pairSeparator, pairSeparatorInd + 1);
        pairSeparatorInd = pairSeparatorInd == -1 ? input.length() : pairSeparatorInd;
        start = end + 1;
      }
      return baggage;
    }

    @Override
    public void accept(String key, String value) {
      if (key != null
          && key.equalsIgnoreCase(BAGGAGE_KEY)) { // Only process tags that are relevant to baggage
        Map<String, String> baggage = parseBaggageHeaders(value);
        if (!baggage.isEmpty()) {
          extractedContext = BaggageContext.create(baggage);
        }
      }
    }
  }
}
