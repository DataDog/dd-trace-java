package datadog.trace.core.baggage;

import datadog.context.Context;
import datadog.context.propagation.CarrierSetter;
import datadog.context.propagation.CarrierVisitor;
import datadog.context.propagation.Propagator;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.BaggageContext;
import datadog.trace.core.util.PercentEscaper;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import javax.annotation.ParametersAreNonnullByDefault;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ParametersAreNonnullByDefault
public class BaggagePropagator implements Propagator {
  private static final Logger log = LoggerFactory.getLogger(BaggagePropagator.class);
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
    config = Config.get();
  }

  @Override
  public <C> void inject(Context context, C carrier, CarrierSetter<C> setter) {
    int maxItems = config.getTraceBaggageMaxItems();
    int maxBytes = config.getTraceBaggageMaxBytes();
    //noinspection ConstantValue
    if (!this.injectBaggage
        || maxItems == 0
        || maxBytes == 0
        || context == null
        || carrier == null
        || setter == null) {
      return;
    }

    BaggageContext baggageContext = BaggageContext.fromContext(context);
    if (baggageContext == null) {
      log.debug("BaggageContext instance is missing from the following context {}", context);
      return;
    }

    String baggageHeader = baggageContext.getW3cBaggageHeader();
    if (baggageHeader != null) {
      setter.set(carrier, BAGGAGE_KEY, baggageHeader);
      return;
    }

    int processedBaggage = 0;
    int currentBytes = 0;
    StringBuilder baggageText = new StringBuilder();
    for (final Map.Entry<String, String> entry : baggageContext.asMap().entrySet()) {
      AtomicInteger pairSize = new AtomicInteger(1);
      // if there are already baggage items processed, add and allocate bytes for a comma
      if (processedBaggage != 0) {
        baggageText.append(',');
        pairSize.incrementAndGet();
      }

      baggageText.append(UTF_ESCAPER.escapeKey(entry.getKey(), pairSize));
      baggageText.append('=');
      baggageText.append(UTF_ESCAPER.escapeValue(entry.getValue(), pairSize));

      processedBaggage++;
      // reached the max number of baggage items allowed
      if (processedBaggage == maxItems) {
        break;
      }
      // Drop newest k/v pair if adding it leads to exceeding the limit
      if (currentBytes + pairSize.get() > maxBytes) {
        baggageText.setLength(currentBytes);
        break;
      }
      currentBytes += pairSize.get();
    }

    String baggageString = baggageText.toString();
    baggageContext.setW3cBaggageHeader(baggageString);
    setter.set(carrier, BAGGAGE_KEY, baggageString);
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
      // Only process tags that are relevant to baggage
      if (key != null && key.equalsIgnoreCase(BAGGAGE_KEY)) {
        Map<String, String> baggage = parseBaggageHeaders(value);
        if (!baggage.isEmpty()) {
          extractedContext = BaggageContext.create(baggage, value);
        }
      }
    }
  }
}
