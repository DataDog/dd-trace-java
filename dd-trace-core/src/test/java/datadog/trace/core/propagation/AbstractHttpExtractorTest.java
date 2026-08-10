package datadog.trace.core.propagation;

import static datadog.trace.api.config.TracerConfig.PROPAGATION_EXTRACT_LOG_HEADER_NAMES_ENABLED;
import static java.util.Collections.singletonMap;

import datadog.trace.api.Config;
import datadog.trace.api.DynamicConfig;
import datadog.trace.api.TraceConfig;
import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.util.DDJavaSpecification;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/** This class is a base test class for the {@link HttpCodec.Extractor} tests. */
@WithConfig(key = PROPAGATION_EXTRACT_LOG_HEADER_NAMES_ENABLED, value = "true")
abstract class AbstractHttpExtractorTest extends DDJavaSpecification {
  protected static final String SOME_HEADER = "SOME_HEADER";
  protected static final String SOME_TAG = "some-tag";
  protected static final String SOME_CUSTOM_BAGGAGE_HEADER = "SOME_CUSTOM_BAGGAGE_HEADER";
  protected static final String SOME_CUSTOM_BAGGAGE_HEADER_2 = "SOME_CUSTOM_BAGGAGE_HEADER_2";
  protected static final String SOME_BAGGAGE = "some-baggage";
  protected static final String SOME_CASE_SENSITIVE_BAGGAGE = "some-CaseSensitive-baggage";
  protected static final String SOME_ARBITRARY_HEADER = "SOME_ARBITRARY_HEADER";

  protected HttpCodec.Extractor extractor;

  /** Creates the extractor for the propagation style under test. */
  protected abstract HttpCodec.Extractor newExtractor(
      Config config, Supplier<TraceConfig> traceConfigSupplier);

  @BeforeEach
  void setupExtractor() {
    this.extractor = buildExtractor(this::newExtractor);
  }

  @AfterEach
  void cleanupExtractor() {
    if (this.extractor != null) {
      this.extractor.cleanup();
    }
  }

  /** Builds an extractor with a test trace config (a basic baggage mapping and header tags). */
  static HttpCodec.Extractor buildExtractor(
      BiFunction<Config, Supplier<TraceConfig>, HttpCodec.Extractor> factory) {
    Map<String, String> baggageMapping = new HashMap<>();
    baggageMapping.put(SOME_CUSTOM_BAGGAGE_HEADER, SOME_BAGGAGE);
    baggageMapping.put(SOME_CUSTOM_BAGGAGE_HEADER_2, SOME_CASE_SENSITIVE_BAGGAGE);
    DynamicConfig<DynamicConfig.Snapshot> dynamicConfig =
        DynamicConfig.create()
            .setHeaderTags(singletonMap(SOME_HEADER, SOME_TAG))
            .setBaggageMapping(baggageMapping)
            .apply();
    return factory.apply(Config.get(), dynamicConfig::captureTraceConfig);
  }
}
