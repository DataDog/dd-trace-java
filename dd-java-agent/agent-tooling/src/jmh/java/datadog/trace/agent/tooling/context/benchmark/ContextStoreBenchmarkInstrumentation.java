package datadog.trace.agent.tooling.context.benchmark;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.muzzle.ReferenceMatcher;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

public final class ContextStoreBenchmarkInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {
  private static final String KEY_TYPE = "context.benchmark.ContextStoreBenchmarkKey";
  private static final String CONTEXT_TYPE =
      "datadog.trace.agent.tooling.context.benchmark.ContextStoreBenchmarkInstrumentation$State";
  private static final String STORE1_KEY_TYPE = "context.benchmark.Store1Key";
  private static final String STORE1_CONTEXT_TYPE = CONTEXT_TYPE + "1";
  private static final String STORE2_KEY_TYPE = "context.benchmark.Store2Key";
  private static final String STORE2_CONTEXT_TYPE = CONTEXT_TYPE + "2";
  private static final String STORE3_KEY_TYPE = "context.benchmark.Store3Key";
  private static final String STORE3_CONTEXT_TYPE = CONTEXT_TYPE + "3";

  public ContextStoreBenchmarkInstrumentation() {
    super("context-store-benchmark");
  }

  @Override
  public String hierarchyMarkerType() {
    return KEY_TYPE;
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(KEY_TYPE));
  }

  @Override
  public Map<String, String> contextStore() {
    Map<String, String> stores = new LinkedHashMap<>();
    stores.put(KEY_TYPE, CONTEXT_TYPE);
    stores.put(STORE1_KEY_TYPE, STORE1_CONTEXT_TYPE);
    stores.put(STORE2_KEY_TYPE, STORE2_CONTEXT_TYPE);
    stores.put(STORE3_KEY_TYPE, STORE3_CONTEXT_TYPE);
    return Collections.unmodifiableMap(stores);
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(named("read"), getClass().getName() + "$ReadAdvice");
    transformer.applyAdvice(named("write"), getClass().getName() + "$WriteAdvice");
    transformer.applyAdvice(named("getContext"), getClass().getName() + "$GetContextAdvice");
    transformer.applyAdvice(named("putContext"), getClass().getName() + "$PutContextAdvice");
    transformer.applyAdvice(named("removeContext"), getClass().getName() + "$RemoveContextAdvice");
  }

  public static class State {
    public final int value;

    public State(int value) {
      this.value = value;
    }
  }

  public static final class State1 extends State {
    public State1(int value) {
      super(value);
    }
  }

  public static final class State2 extends State {
    public State2(int value) {
      super(value);
    }
  }

  public static final class State3 extends State {
    public State3(int value) {
      super(value);
    }
  }

  public static final class ReadAdvice {
    @Advice.OnMethodExit
    public static void exit(@Advice.This Object key, @Advice.Return(readOnly = false) int result) {
      ContextStore<Object, State> store = InstrumentationContext.get(KEY_TYPE, CONTEXT_TYPE);
      result = store.get(key).value;
    }
  }

  public static final class WriteAdvice {
    @Advice.OnMethodExit
    public static void exit(@Advice.This Object key, @Advice.Argument(0) Object context) {
      ContextStore<Object, State> store = InstrumentationContext.get(KEY_TYPE, CONTEXT_TYPE);
      store.put(key, (State) context);
    }
  }

  public static final class GetContextAdvice {
    @Advice.OnMethodExit
    public static void exit(
        @Advice.This Object key,
        @Advice.Argument(0) int storeIndex,
        @Advice.Return(readOnly = false) Object context) {
      ContextStore<Object, State> store;
      switch (storeIndex) {
        case 0:
          store = InstrumentationContext.get(KEY_TYPE, CONTEXT_TYPE);
          break;
        case 1:
          store = InstrumentationContext.get(STORE1_KEY_TYPE, STORE1_CONTEXT_TYPE);
          break;
        case 2:
          store = InstrumentationContext.get(STORE2_KEY_TYPE, STORE2_CONTEXT_TYPE);
          break;
        case 3:
          store = InstrumentationContext.get(STORE3_KEY_TYPE, STORE3_CONTEXT_TYPE);
          break;
        default:
          throw new IllegalArgumentException("unsupported store index: " + storeIndex);
      }
      context = store.get(key);
    }
  }

  public static final class PutContextAdvice {
    @Advice.OnMethodExit
    public static void exit(
        @Advice.This Object key,
        @Advice.Argument(0) int storeIndex,
        @Advice.Argument(1) Object context) {
      ContextStore<Object, State> store;
      switch (storeIndex) {
        case 0:
          store = InstrumentationContext.get(KEY_TYPE, CONTEXT_TYPE);
          break;
        case 1:
          store = InstrumentationContext.get(STORE1_KEY_TYPE, STORE1_CONTEXT_TYPE);
          break;
        case 2:
          store = InstrumentationContext.get(STORE2_KEY_TYPE, STORE2_CONTEXT_TYPE);
          break;
        case 3:
          store = InstrumentationContext.get(STORE3_KEY_TYPE, STORE3_CONTEXT_TYPE);
          break;
        default:
          throw new IllegalArgumentException("unsupported store index: " + storeIndex);
      }
      store.put(key, (State) context);
    }
  }

  public static final class RemoveContextAdvice {
    @Advice.OnMethodExit
    public static void exit(
        @Advice.This Object key,
        @Advice.Argument(0) int storeIndex,
        @Advice.Return(readOnly = false) Object context) {
      ContextStore<Object, State> store;
      switch (storeIndex) {
        case 0:
          store = InstrumentationContext.get(KEY_TYPE, CONTEXT_TYPE);
          break;
        case 1:
          store = InstrumentationContext.get(STORE1_KEY_TYPE, STORE1_CONTEXT_TYPE);
          break;
        case 2:
          store = InstrumentationContext.get(STORE2_KEY_TYPE, STORE2_CONTEXT_TYPE);
          break;
        case 3:
          store = InstrumentationContext.get(STORE3_KEY_TYPE, STORE3_CONTEXT_TYPE);
          break;
        default:
          throw new IllegalArgumentException("unsupported store index: " + storeIndex);
      }
      context = store.remove(key);
    }
  }

  public static final class Muzzle {
    public static ReferenceMatcher create() {
      return ReferenceMatcher.NO_REFERENCES;
    }
  }
}
