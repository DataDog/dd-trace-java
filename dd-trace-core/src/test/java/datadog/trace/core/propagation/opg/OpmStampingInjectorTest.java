package datadog.trace.core.propagation.opg;

import static datadog.trace.core.propagation.PropagationTags.HeaderType.DATADOG;
import static datadog.trace.core.propagation.PropagationTags.HeaderType.W3C;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.context.propagation.CarrierSetter;
import datadog.trace.core.DDSpanContext;
import datadog.trace.core.propagation.HttpCodec;
import datadog.trace.core.propagation.PropagationTags;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("OpmStampingInjector")
class OpmStampingInjectorTest {
  @Test
  @DisplayName("stamps the local OPM into PropagationTags before delegating")
  void stampsLocalOpm() {
    PropagationTags.Factory factory = PropagationTags.factory();
    PropagationTags tags = factory.fromHeaderValue(W3C, "");

    DDSpanContext ctx = mock(DDSpanContext.class);
    when(ctx.getPropagationTags()).thenReturn(tags);

    HttpCodec.Injector delegate = new NoopInjector();
    Supplier<String> supplier = () -> "local-opm-1";
    OpmStampingInjector wrapped = new OpmStampingInjector(delegate, supplier);

    Map<String, String> carrier = new HashMap<>();
    wrapped.inject(ctx, carrier, Map::put);

    assertNotNull(tags.getOrgPropagationMarker());
    assertEquals("local-opm-1", tags.getOrgPropagationMarker().toString());
  }

  @Test
  @DisplayName("with null supplier value, leaves PropagationTags untouched")
  void supplierNullLeavesTagsUntouched() {
    PropagationTags.Factory factory = PropagationTags.factory();
    PropagationTags tags = factory.fromHeaderValue(DATADOG, "_dd.p.opm=upstream-abc");

    DDSpanContext ctx = mock(DDSpanContext.class);
    when(ctx.getPropagationTags()).thenReturn(tags);

    HttpCodec.Injector delegate = new NoopInjector();
    OpmStampingInjector wrapped = new OpmStampingInjector(delegate, () -> null);

    Map<String, String> carrier = new HashMap<>();
    wrapped.inject(ctx, carrier, Map::put);

    assertNotNull(tags.getOrgPropagationMarker());
    assertEquals("upstream-abc", tags.getOrgPropagationMarker().toString());
  }

  @Test
  @DisplayName("local supplier value overrides any inherited OPM")
  void localOpmOverridesInherited() {
    PropagationTags.Factory factory = PropagationTags.factory();
    PropagationTags tags = factory.fromHeaderValue(DATADOG, "_dd.p.opm=upstream-abc");

    DDSpanContext ctx = mock(DDSpanContext.class);
    when(ctx.getPropagationTags()).thenReturn(tags);

    HttpCodec.Injector delegate = new NoopInjector();
    OpmStampingInjector wrapped = new OpmStampingInjector(delegate, () -> "local-xyz");

    Map<String, String> carrier = new HashMap<>();
    wrapped.inject(ctx, carrier, Map::put);

    assertEquals("local-xyz", tags.getOrgPropagationMarker().toString());
  }

  @Test
  @DisplayName("delegate is invoked exactly once per inject call")
  void delegateInvoked() {
    PropagationTags.Factory factory = PropagationTags.factory();
    PropagationTags tags = factory.empty();

    DDSpanContext ctx = mock(DDSpanContext.class);
    when(ctx.getPropagationTags()).thenReturn(tags);

    CountingInjector delegate = new CountingInjector();
    OpmStampingInjector wrapped = new OpmStampingInjector(delegate, () -> null);

    wrapped.inject(ctx, new HashMap<>(), Map::put);
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
}
