package datadog.trace.core.propagation;

import static datadog.trace.api.TracePropagationStyle.BAGGAGE;

import datadog.trace.api.Config;
import datadog.trace.api.TraceConfig;
import datadog.trace.api.TracePropagationStyle;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.core.DDSpanContext;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A codec designed for HTTP transport via headers using Datadog headers */
class BaggageHttpCodec {
  private static final Logger log = LoggerFactory.getLogger(BaggageHttpCodec.class);

  static final String BAGGAGE_KEY = "baggage";
  private static final int MAX_CHARACTER_SIZE = 4;

  private BaggageHttpCodec() {
    // This class should not be created. This also makes code coverage checks happy.
  }

  public static HttpCodec.Injector newInjector(Map<String, String> invertedBaggageMapping) {
    return new Injector(invertedBaggageMapping);
  }

  private static class Injector implements HttpCodec.Injector {

    private final Map<String, String> invertedBaggageMapping;

    public Injector(Map<String, String> invertedBaggageMapping) {
      assert invertedBaggageMapping != null;
      this.invertedBaggageMapping = invertedBaggageMapping;
    }

    @Override
    public <C> void inject(
        final DDSpanContext context, final C carrier, final AgentPropagation.Setter<C> setter) {
      Config config = Config.get();

      StringBuilder baggageText = new StringBuilder();
      int processedBaggage = 0;
      int currentBytes = 0;
      int maxItems = config.getTraceBaggageMaxItems();
      int maxBytes = config.getTraceBaggageMaxBytes();
      int currentCharacters = 0;
      int maxSafeCharacters = maxBytes / MAX_CHARACTER_SIZE;
      for (final Map.Entry<String, String> entry : context.baggageItems()) {
        if (processedBaggage >= maxItems) {
          break;
        }
        StringBuilder currentText = new StringBuilder();
        if (processedBaggage != 0) {
          currentText.append(',');
        }

        currentText.append(HttpCodec.encodeBaggage(entry.getKey()));
        currentText.append('=');
        currentText.append(HttpCodec.encodeBaggage(entry.getValue()));

        // worst case check
        if (currentCharacters + currentText.length() <= maxSafeCharacters) {
          currentCharacters += currentText.length();
        } else {
          if (currentBytes
              == 0) { // special case to calculate byte size after surpassing worst-case number of
            // characters
            currentBytes = baggageText.toString().getBytes(StandardCharsets.UTF_8).length;
          }
          int byteSize =
              currentText
                  .toString()
                  .getBytes(StandardCharsets.UTF_8)
                  .length; // find largest possible byte size for UTF encoded characters and only do
          // size checking after we hit this worst case scenario
          if (byteSize + currentBytes > maxBytes) {
            break;
          }
          currentBytes += byteSize;
        }
        baggageText.append(currentText);
        processedBaggage++;
      }

      setter.set(carrier, BAGGAGE_KEY, baggageText.toString());
    }
  }

  public static HttpCodec.Extractor newExtractor(
      Config config, Supplier<TraceConfig> traceConfigSupplier) {
    return new TagContextExtractor(
        traceConfigSupplier, () -> new BaggageContextInterpreter(config));
  }

  private static class BaggageContextInterpreter extends ContextInterpreter {

    private BaggageContextInterpreter(Config config) {
      super(config);
    }

    @Override
    public TracePropagationStyle style() {
      return BAGGAGE;
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
        if (kvSeparatorInd > end) { // value is missing
          return Collections.emptyMap();
        }
        String key = HttpCodec.decode(input.substring(start, kvSeparatorInd).trim());
        String value = HttpCodec.decode(input.substring(kvSeparatorInd + 1, end).trim());
        if (key.isEmpty() || value.isEmpty()) {
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
    public boolean accept(String key, String value) {
      if (null == key || key.isEmpty()) {
        return true;
      }
      if (LOG_EXTRACT_HEADER_NAMES) {
        log.debug("Header: {}", key);
      }

      if (key.equalsIgnoreCase(BAGGAGE_KEY)) { // Only process tags that are relevant to baggage
        baggage = parseBaggageHeaders(value);
      }
      return true;
    }

    @Override
    protected TagContext build() {
      return super.build();
    }
  }
}
