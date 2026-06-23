package datadog.trace.core.baggage;

import datadog.context.Context;
import datadog.context.propagation.CarrierSetter;
import datadog.context.propagation.CarrierVisitor;
import datadog.context.propagation.Propagator;
import datadog.trace.api.Config;
import datadog.trace.api.metrics.BaggageMetrics;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.Baggage;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.core.util.PercentEscaper;
import datadog.trace.core.util.PercentEscaper.Escaped;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ParametersAreNonnullByDefault
public class BaggagePropagator implements Propagator {
  private static final Logger LOG = LoggerFactory.getLogger(BaggagePropagator.class);
  private static final PercentEscaper UTF_ESCAPER = PercentEscaper.create();
  private static final BaggageMetrics BAGGAGE_METRICS = BaggageMetrics.getInstance();
  static final String BAGGAGE_KEY = "baggage";
  private final boolean injectBaggage;
  private final boolean extractBaggage;
  private final int maxItems;
  private final int maxBytes;

  public BaggagePropagator(Config config) {
    this(
        config.isBaggageInject(),
        config.isBaggageExtract(),
        config.getTraceBaggageMaxItems(),
        config.getTraceBaggageMaxBytes());
  }

  // use primarily for testing purposes
  BaggagePropagator(boolean injectBaggage, boolean extractBaggage, int maxItems, int maxBytes) {
    this.injectBaggage = injectBaggage;
    this.extractBaggage = extractBaggage;
    this.maxItems = maxItems;
    this.maxBytes = maxBytes;
  }

  @Override
  public <C> void inject(Context context, C carrier, CarrierSetter<C> setter) {
    Baggage baggage;
    if (!this.injectBaggage
        || this.maxItems == 0
        || this.maxBytes == 0
        || context == null
        || carrier == null
        || setter == null
        || (baggage = Baggage.fromContext(context)) == null) {
      return;
    }

    // Inject cached header if any as optimized path
    String headerValue = baggage.getW3cHeader();
    if (headerValue != null) {
      setter.set(carrier, BAGGAGE_KEY, headerValue);
      return;
    }

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
      if (processedItems == this.maxItems) {
        BAGGAGE_METRICS.onBaggageTruncatedByInjectItemLimit();
        break;
      }
      // Drop newest k/v pair if adding it leads to exceeding the limit
      if (currentBytes + escapedKey.size + escapedVal.size + extraBytes > this.maxBytes) {
        baggageText.setLength(currentBytes);
        BAGGAGE_METRICS.onBaggageTruncatedByInjectByteLimit();
        break;
      }
      currentBytes += escapedKey.size + escapedVal.size + extraBytes;
    }

    headerValue = baggageText.toString();
    // Save header as cache to re-inject it later if baggage did not change
    baggage.setW3cHeader(headerValue);
    setter.set(carrier, BAGGAGE_KEY, headerValue);

    // Record successful baggage injection for telemetry
    BAGGAGE_METRICS.onBaggageInjected();
  }

  @Override
  public <C> Context extract(Context context, C carrier, CarrierVisitor<C> visitor) {
    if (!this.extractBaggage || context == null || carrier == null || visitor == null) {
      return context;
    }
    BaggageExtractor baggageExtractor = new BaggageExtractor();
    visitor.forEachKeyValue(carrier, baggageExtractor);
    Baggage baggage = baggageExtractor.extracted;
    if (baggage == null) {
      return context;
    }

    // Record successful baggage extraction for telemetry
    BAGGAGE_METRICS.onBaggageExtracted();

    // TODO: consider a better way to link baggage with the extracted (legacy) TagContext
    AgentSpan extractedSpan = AgentSpan.fromContext(context);
    if (extractedSpan != null) {
      AgentSpanContext extractedSpanContext = extractedSpan.spanContext();
      if (extractedSpanContext instanceof TagContext) {
        ((TagContext) extractedSpanContext).setW3CBaggage(baggage);
      }
    }

    return context.with(baggage);
  }

  private class BaggageExtractor implements BiConsumer<String, String> {
    private static final char KEY_VALUE_SEPARATOR = '=';
    private static final char PAIR_SEPARATOR = ',';
    @Nullable private Baggage extracted;

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

    private Baggage parseBaggageHeaders(String input) {
      Map<String, String> baggage = new HashMap<>();
      int start = 0;
      String w3cHeader = input;
      int pairSeparatorInd = input.indexOf(PAIR_SEPARATOR);
      pairSeparatorInd = pairSeparatorInd == -1 ? input.length() : pairSeparatorInd;
      int kvSeparatorInd = input.indexOf(KEY_VALUE_SEPARATOR);
      while (kvSeparatorInd != -1) {
        int end = pairSeparatorInd;
        boolean limitReached = false;
        if (baggage.size() >= maxItems) {
          limitReached = true;
          BAGGAGE_METRICS.onBaggageTruncatedByExtractItemLimit();
        } else if (end > maxBytes) {
          limitReached = true;
          BAGGAGE_METRICS.onBaggageTruncatedByExtractByteLimit();
        }
        if (limitReached) {
          // if header was not invalidated already, and we go out of range:
          // - fully invalidate if it's after the first k/v pair,
          // - otherwise ignore from the current k/v separator
          w3cHeader = (w3cHeader == null || start == 0) ? null : w3cHeader.substring(0, start - 1);
          break;
        }
        if (kvSeparatorInd > end) {
          LOG.debug(
              "Dropping baggage headers due to key with no value {}", input.substring(start, end));
          BAGGAGE_METRICS.onBaggageMalformed();
          return null;
        }
        String key = decode(input.substring(start, kvSeparatorInd).trim());
        String value = decode(input.substring(kvSeparatorInd + 1, end).trim());
        if (key.isEmpty() || value.isEmpty()) {
          LOG.debug("Dropping baggage headers due to empty k/v {}:{}", key, value);
          BAGGAGE_METRICS.onBaggageMalformed();
          return null;
        }
        baggage.put(key, value);

        // need to percent-encode non-ascii headers we pass down
        if (w3cHeader != null
            && (UTF_ESCAPER.keyNeedsEncoding(key) || UTF_ESCAPER.valNeedsEncoding(value))) {
          w3cHeader = null;
        }

        kvSeparatorInd = input.indexOf(KEY_VALUE_SEPARATOR, pairSeparatorInd + 1);
        pairSeparatorInd = input.indexOf(PAIR_SEPARATOR, pairSeparatorInd + 1);
        pairSeparatorInd = pairSeparatorInd == -1 ? input.length() : pairSeparatorInd;
        start = end + 1;
      }

      return baggage.isEmpty() ? null : Baggage.create(baggage, w3cHeader);
    }

    @Override
    public void accept(String key, @Nullable String value) {
      if (value == null) {
        return;
      }
      // Only process tags that are relevant to baggage
      if (BAGGAGE_KEY.equalsIgnoreCase(key)) {
        Baggage parsed = parseBaggageHeaders(value);
        if (parsed != null) {
          this.extracted = parsed;
        }
      }
    }
  }
}
