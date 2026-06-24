package datadog.trace.core.propagation.opg;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.trace.api.Config;
import datadog.trace.core.monitor.HealthMetrics;
import datadog.trace.core.propagation.HttpCodec;
import datadog.trace.core.propagation.PropagationTags;
import java.util.Collections;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("OrgGuard factory gating")
class OrgGuardTest {

  private static final Supplier<String> LOCAL_OPM = () -> "L";

  @Test
  @DisplayName("disabled: decorate methods return the input unchanged")
  void disabledIsZeroCost() {
    Config config = mock(Config.class, RETURNS_DEFAULTS);
    when(config.isTraceOrgGuardEnabled()).thenReturn(false);

    OrgGuard orgGuard =
        OrgGuard.create(config, LOCAL_OPM, PropagationTags.factory(), mock(HealthMetrics.class));

    HttpCodec.Extractor extractor = mock(HttpCodec.Extractor.class);
    HttpCodec.Injector injector = mock(HttpCodec.Injector.class);

    assertSame(extractor, orgGuard.decorateExtractor(extractor));
    assertSame(injector, orgGuard.decorateInjector(injector));
  }

  @Test
  @DisplayName("enabled: decorate methods wrap with the OPG decorators")
  void enabledWrapsBothSides() {
    Config config = mock(Config.class, RETURNS_DEFAULTS);
    when(config.isTraceOrgGuardEnabled()).thenReturn(true);
    when(config.isTraceOrgGuardStrict()).thenReturn(false);
    when(config.getTraceOrgGuardTrustedOpms()).thenReturn(Collections.emptySet());

    OrgGuard orgGuard =
        OrgGuard.create(config, LOCAL_OPM, PropagationTags.factory(), mock(HealthMetrics.class));

    HttpCodec.Extractor extractor = mock(HttpCodec.Extractor.class);
    HttpCodec.Injector injector = mock(HttpCodec.Injector.class);

    assertInstanceOf(OrgGuardEnforcingExtractor.class, orgGuard.decorateExtractor(extractor));
    assertInstanceOf(OpmStampingInjector.class, orgGuard.decorateInjector(injector));
  }
}
