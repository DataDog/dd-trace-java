package datadog.trace.core.baggage;

import static java.util.Collections.emptyMap;

import datadog.context.Context;
import datadog.context.propagation.CarrierSetter;
import datadog.context.propagation.CarrierVisitor;
import datadog.context.propagation.Propagator;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.Baggage;
import datadog.trace.core.util.PercentEscaper;
import datadog.trace.core.util.PercentEscaper.Escaped;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import javax.annotation.ParametersAreNonnullByDefault;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ParametersAreNonnullByDefault
public class BaggagePropagator implements Propagator {
  private static final Logger LOG = LoggerFactory.getLogger(BaggagePropagator.class);
  private static final PercentEscaper UTF_ESCAPER = PercentEscaper.create();
  static final String BAGGAGE_KEY = "baggage";
  private final Config config;
  private final boolean injectBaggage;
  private final boolean extractBaggage;

  public BaggagePropagator(Config config) {
    this.injectBaggage = config.isBaggageInject();
    this.extractBaggage = config.isBaggageExtract();
    this.config = config;
  }

  // use primarily for testing purposes
  public BaggagePropagator(boolean injectBaggage, boolean extractBaggage) {
    this.injectBaggage = injectBaggage;
    this.extractBaggage = extractBaggage;
    this.config = Config.get();
  }

  @Override
  public <C> void inject(Context context, C carrier, CarrierSetter<C> setter) {
    int maxItems = this.config.getTraceBaggageMaxItems();
    int maxBytes = this.config.getTraceBaggageMaxBytes();
    //noinspection ConstantValue
    if (!this.injectBaggage
        || maxItems == 0
        || maxBytes == 0
        || context == null
        || carrier == null
        || setter == null) {
      return;
    }

    Baggage baggage = Baggage.fromContext(context);
    if (baggage == null) {
      return;
    }

    String headerValue = baggage.getW3cHeader();
//    if (headerValue != null) {
//      System.out.println("Returning cached value immediately");
//      setter.set(carrier, BAGGAGE_KEY, headerValue);
//      return;
//    }e

    int processedItems = 0;
    int currentBytes = 0;
    StringBuilder baggageText = new StringBuilder();
    for (final Map.Entry<String, String> entry : baggage.asMap().entrySet()) {
      // if there are already baggage items processed, add and allocate bytes for a comma
      int extraBytes = 1;
      if (processedItems != 0) {
        baggageText.append(',');
        extraBytes++;
      }

      Escaped escapedKey = UTF_ESCAPER.escapeKey(entry.getKey());
      Escaped escapedVal = UTF_ESCAPER.escapeValue(entry.getValue());

      baggageText.append(escapedKey.data);
      baggageText.append('=');
      baggageText.append(escapedVal.data);

      processedItems++;
      // reached the max number of baggage items allowed
      if (processedItems == maxItems) {
        break;
      }
      // Drop newest k/v pair if adding it leads to exceeding the limit
      if (currentBytes + escapedKey.size + escapedVal.size + extraBytes > maxBytes) {
        baggageText.setLength(currentBytes);
        break;
      }
      currentBytes += escapedKey.size + escapedVal.size + extraBytes;
    }

    headerValue = baggageText.toString();
    baggage.setW3cHeader(headerValue);
    setter.set(carrier, BAGGAGE_KEY, headerValue);
  }

  @Override
  public <C> Context extract(Context context, C carrier, CarrierVisitor<C> visitor) {
    //noinspection ConstantValue
    if (!this.extractBaggage || context == null || carrier == null || visitor == null) {
      return context;
    }
    BaggageExtractor baggageExtractor = new BaggageExtractor();
    visitor.forEachKeyValue(carrier, baggageExtractor);
    return baggageExtractor.extracted == null ? context : context.with(baggageExtractor.extracted);
  }

  private static class BaggageExtractor implements BiConsumer<String, String> {
    private static final char KEY_VALUE_SEPARATOR = '=';
    private static final char PAIR_SEPARATOR = ',';
    private Baggage extracted;

    private BaggageExtractor() {}

    /** URL decode value */
    private String decode(final String value) {
      String decoded = value;
      try {
        decoded = URLDecoder.decode(value, "UTF-8");
      } catch (final UnsupportedEncodingException | IllegalArgumentException e) {
        LOG.debug("Failed to decode {}", value);
      }
      return decoded;
    }

    private Map<String, String> parseBaggageHeaders(String input) {
      Map<String, String> baggage = new HashMap<>();
      int start = 0;
      int pairSeparatorInd = input.indexOf(PAIR_SEPARATOR);
      pairSeparatorInd = pairSeparatorInd == -1 ? input.length() : pairSeparatorInd;
      int kvSeparatorInd = input.indexOf(KEY_VALUE_SEPARATOR);
      while (kvSeparatorInd != -1) {
        int end = pairSeparatorInd;
        if (kvSeparatorInd > end) {
          LOG.debug(
              "Dropping baggage headers due to key with no value {}", input.substring(start, end));
          return emptyMap();
        }
        String key = decode(input.substring(start, kvSeparatorInd).trim());
        String value = decode(input.substring(kvSeparatorInd + 1, end).trim());
        if (key.isEmpty() || value.isEmpty()) {
          LOG.debug("Dropping baggage headers due to empty k/v {}:{}", key, value);
          return emptyMap();
        }
        baggage.put(key, value);

        kvSeparatorInd = input.indexOf(KEY_VALUE_SEPARATOR, pairSeparatorInd + 1);
        pairSeparatorInd = input.indexOf(PAIR_SEPARATOR, pairSeparatorInd + 1);
        pairSeparatorInd = pairSeparatorInd == -1 ? input.length() : pairSeparatorInd;
        start = end + 1;
      }
      return baggage;
    }

    @Override
    public void accept(String key, String value) {
      System.out.println("key: " + key + " ;value: " + value);

      // Only process tags that are relevant to baggage
      if (BAGGAGE_KEY.equalsIgnoreCase(key)) {
        Map<String, String> baggage = parseBaggageHeaders(value);
        if (!baggage.isEmpty()) {
          this.extracted = Baggage.create(baggage, value);
        }
      }
    }
  }
}
