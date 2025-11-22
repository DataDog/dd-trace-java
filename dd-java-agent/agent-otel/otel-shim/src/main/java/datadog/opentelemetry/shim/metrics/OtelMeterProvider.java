package datadog.opentelemetry.shim.metrics;

import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.MeterBuilder;
import io.opentelemetry.api.metrics.MeterProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.ParametersAreNonnullByDefault;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OtelMeterProvider implements MeterProvider {
  private static final Logger LOGGER = LoggerFactory.getLogger(OtelMeterProvider.class);
  private static final String DEFAULT_METER_NAME = "";
  public static final MeterProvider INSTANCE = new OtelMeterProvider();
  /** Meter instances, indexed by instrumentation scope name. */
  private final Map<String, List<Meter>> scopedMeters = new ConcurrentHashMap<>();

  @Override
  @ParametersAreNonnullByDefault
  public Meter get(String instrumentationScopeName) {
    return get(instrumentationScopeName, null);
  }

  public Meter get(String instrumentationScopeName, String instrumentationVersion) {
    return get(instrumentationScopeName, instrumentationVersion, null);
  }

  public Meter get(
      String instrumentationScopeName, String instrumentationVersion, String urlSchema) {
    List<Meter> meters = this.scopedMeters.get(instrumentationScopeName);
    if (meters != null) {
      for (Meter meter : meters) {
        if ((meter instanceof OtelMeter)
            && ((OtelMeter) meter)
                .match(instrumentationScopeName, instrumentationVersion, urlSchema)) {
          return meter;
        }
      }
    }
    Meter meter =
        meterBuilder(instrumentationScopeName)
            .setInstrumentationVersion(instrumentationVersion)
            .setSchemaUrl(urlSchema)
            .build();
    this.scopedMeters.put(instrumentationScopeName, new ArrayList<>());
    this.scopedMeters.get(instrumentationScopeName).add(meter);

    return meter;
  }

  @Override
  public MeterBuilder meterBuilder(String instrumentationScopeName) {
    if (instrumentationScopeName.trim().isEmpty()) {
      LOGGER.debug("Meter requested without instrumentation scope name.");
      instrumentationScopeName = DEFAULT_METER_NAME;
    }
    return new OtelMeterBuilder(instrumentationScopeName);
  }
}
