package datadog.trace.bootstrap.instrumentation.iast;

import static datadog.trace.api.iast.SourceTypes.REQUEST_PARAMETER_NAME;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Taintable.Source;
import datadog.trace.api.iast.propagation.PropagationModule;
import datadog.trace.bootstrap.ContextStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NamedContextTest {

  private PropagationModule module;
  private ContextStore<Object, NamedContext> store;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setup() {
    module = mock(PropagationModule.class);
    InstrumentationBridge.registerIastModule(module);
    store = mock(ContextStore.class);
  }

  @Test
  void testThatTheContextTaintsNamesAndValues() {
    Source source = new SourceImpl(REQUEST_PARAMETER_NAME);
    Object target = new Object();
    String name = "name";
    String value = "value";
    when(module.findSource(target)).thenReturn(source);

    // when
    NamedContext context = NamedContext.getOrCreate(store, target);

    // then
    verify(store).get(target);
    verify(module).findSource(target);
    verify(store).put(eq(target), any());
    verifyNoMoreInteractions(module, store);

    // when
    context.taintName(name);

    // then
    verify(module).taintString(any(), eq(name), eq(source.getOrigin()), eq(name), isNull());
    verifyNoMoreInteractions(module, store);

    // when
    context.taintName(name);

    // then
    verifyNoMoreInteractions(module, store);

    // when
    context.taintValue(value);

    // then
    verify(module).taintString(any(), eq(value), eq(source.getOrigin()), eq(name), isNull());
    verifyNoMoreInteractions(module, store);
  }

  @Test
  void testContextForNonTainted() {
    String target = "test";

    // when
    NamedContext context = NamedContext.getOrCreate(store, target);

    // then
    verify(store).get(target);
    verify(module).findSource(target);
    verify(store).put(eq(target), any());
    verifyNoMoreInteractions(module, store);

    // when
    context.taintName("name");

    // then
    verifyNoMoreInteractions(module, store);

    // when
    context.taintValue("value");

    // then
    verifyNoMoreInteractions(module, store);
  }

  private static class SourceImpl implements Source {
    private final byte origin;

    SourceImpl(byte origin) {
      this.origin = origin;
    }

    @Override
    public byte getOrigin() {
      return origin;
    }

    @Override
    public String getName() {
      return null;
    }

    @Override
    public String getValue() {
      return null;
    }
  }
}
