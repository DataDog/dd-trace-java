package datadog.trace.core.propagation;

import dagger.Module;
import dagger.Provides;
import datadog.trace.api.Config;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.inject.Named;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Module
public class PropagationModule {
  private final HttpCodec.Injector injector;
  private final HttpCodec.Extractor extractor;

  public PropagationModule() {
    this(null, null);
  }

  public PropagationModule(final HttpCodec.Injector injector, final HttpCodec.Extractor extractor) {
    this.injector = injector;
    this.extractor = extractor;
  }

  @Singleton
  @Provides
  HttpCodec.Injector injector(final Config config) {
    if (injector != null) {
      return injector;
    }
    final List<HttpCodec.Injector> injectors = new ArrayList<>();
    for (final Config.PropagationStyle style : config.getPropagationStylesToInject()) {
      if (style == Config.PropagationStyle.DATADOG) {
        injectors.add(new DatadogHttpCodec.Injector());
        continue;
      }
      if (style == Config.PropagationStyle.B3) {
        injectors.add(new B3HttpCodec.Injector());
        continue;
      }
      if (style == Config.PropagationStyle.HAYSTACK) {
        injectors.add(new HaystackHttpCodec.Injector());
        continue;
      }
      log.debug("No implementation found to inject propagation style: {}", style);
    }
    return new HttpCodec.CompoundInjector(injectors);
  }

  @Singleton
  @Provides
  HttpCodec.Extractor extractor(
      final Config config, @Named("taggedHeaders") final Map<String, String> taggedHeaders) {
    if (extractor != null) {
      return extractor;
    }
    final List<HttpCodec.Extractor> extractors = new ArrayList<>();
    for (final Config.PropagationStyle style : config.getPropagationStylesToExtract()) {
      if (style == Config.PropagationStyle.DATADOG) {
        extractors.add(new DatadogHttpCodec.Extractor(taggedHeaders));
        continue;
      }
      if (style == Config.PropagationStyle.B3) {
        extractors.add(new B3HttpCodec.Extractor(taggedHeaders));
        continue;
      }
      if (style == Config.PropagationStyle.HAYSTACK) {
        extractors.add(new HaystackHttpCodec.Extractor(taggedHeaders));
        continue;
      }
      log.debug("No implementation found to extract propagation style: {}", style);
    }
    return new HttpCodec.CompoundExtractor(extractors);
  }
}
