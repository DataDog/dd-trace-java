package datadog.trace.core.propagation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.context.propagation.CarrierSetter;
import datadog.trace.core.DDSpanContext;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import javax.annotation.ParametersAreNonnullByDefault;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

@DisplayName("OpmStampingInjector")
class OpmStampingInjectorTest {

  private static final MapSetter MAP_SETTER = new MapSetter();

  @Test
  @DisplayName("stamps the local OPM into PropagationTags before delegating")
  void stampsLocalOpm() {
    PropagationTags.Factory factory = PropagationTags.factory();
    PropagationTags tags = factory.fromHeaderValue(PropagationTags.HeaderType.W3C, "");

    DDSpanContext ctx = Mockito.mock(DDSpanContext.class);
    Mockito.when(ctx.getPropagationTags()).thenReturn(tags);

    HttpCodec.Injector delegate = new NoopInjector();
    Supplier<String> supplier = () -> "local-opm-1";
    OpmStampingInjector wrapped = new OpmStampingInjector(delegate, supplier);

    Map<String, String> carrier = new HashMap<>();
    wrapped.inject(ctx, carrier, MAP_SETTER);

    assertNotNull(tags.getOrgPropagationMarker());
    assertEquals("local-opm-1", tags.getOrgPropagationMarker().toString());
  }

  @Test
  @DisplayName("with null supplier value, leaves PropagationTags untouched")
  void supplierNullLeavesTagsUntouched() {
    PropagationTags.Factory factory = PropagationTags.factory();
    PropagationTags tags =
        factory.fromHeaderValue(PropagationTags.HeaderType.DATADOG, "_dd.p.opm=upstream-abc");

    DDSpanContext ctx = Mockito.mock(DDSpanContext.class);
    Mockito.when(ctx.getPropagationTags()).thenReturn(tags);

    HttpCodec.Injector delegate = new NoopInjector();
    OpmStampingInjector wrapped = new OpmStampingInjector(delegate, () -> null);

    Map<String, String> carrier = new HashMap<>();
    wrapped.inject(ctx, carrier, MAP_SETTER);

    assertNotNull(tags.getOrgPropagationMarker());
    assertEquals("upstream-abc", tags.getOrgPropagationMarker().toString());
  }

  @Test
  @DisplayName("local supplier value overrides any inherited OPM")
  void localOpmOverridesInherited() {
    PropagationTags.Factory factory = PropagationTags.factory();
    PropagationTags tags =
        factory.fromHeaderValue(PropagationTags.HeaderType.DATADOG, "_dd.p.opm=upstream-abc");

    DDSpanContext ctx = Mockito.mock(DDSpanContext.class);
    Mockito.when(ctx.getPropagationTags()).thenReturn(tags);

    HttpCodec.Injector delegate = new NoopInjector();
    OpmStampingInjector wrapped = new OpmStampingInjector(delegate, () -> "local-xyz");

    Map<String, String> carrier = new HashMap<>();
    wrapped.inject(ctx, carrier, MAP_SETTER);

    assertEquals("local-xyz", tags.getOrgPropagationMarker().toString());
  }

  @Test
  @DisplayName("delegate is invoked exactly once per inject call")
  void delegateInvoked() {
    PropagationTags.Factory factory = PropagationTags.factory();
    PropagationTags tags = factory.empty();

    DDSpanContext ctx = Mockito.mock(DDSpanContext.class);
    Mockito.when(ctx.getPropagationTags()).thenReturn(tags);

    CountingInjector delegate = new CountingInjector();
    OpmStampingInjector wrapped = new OpmStampingInjector(delegate, () -> null);

    wrapped.inject(ctx, new HashMap<>(), MAP_SETTER);
    assertEquals(1, delegate.calls);
    assertNull(tags.getOrgPropagationMarker());
  }

  private static final class NoopInjector implements HttpCodec.Injector {
    @Override
    public <C> void inject(DDSpanContext context, C carrier, CarrierSetter<C> setter) {}
  }

  private static final class CountingInjector implements HttpCodec.Injector {
    int calls = 0;

    @Override
    public <C> void inject(DDSpanContext context, C carrier, CarrierSetter<C> setter) {
      calls++;
    }
  }

  @ParametersAreNonnullByDefault
  private static final class MapSetter implements CarrierSetter<Map<String, String>> {
    @Override
    public void set(Map<String, String> carrier, String key, String value) {
      carrier.put(key, value);
    }
  }
}
