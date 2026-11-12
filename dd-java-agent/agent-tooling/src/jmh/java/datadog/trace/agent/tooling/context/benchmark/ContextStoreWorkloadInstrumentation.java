package datadog.trace.agent.tooling.context.benchmark;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.muzzle.ReferenceMatcher;
import datadog.trace.bootstrap.InstrumentationContext;
import java.util.LinkedHashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/** Declares 16 context stores and exposes them through {@code WorkloadKey.store(int)}. */
public final class ContextStoreWorkloadInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {
  static final int STORE_COUNT = 16;

  private static final String KEY_TYPE = "context.benchmark.workload.WorkloadKey";
  private static final String STORE_KEY_TYPE = "context.benchmark.workload.StoreKeys$S";
  private static final String CONTEXT_TYPE =
      "datadog.trace.agent.tooling.context.benchmark.ContextStoreWorkloadInstrumentation$Context";

  public ContextStoreWorkloadInstrumentation() {
    super("context-store-workload-benchmark");
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
    for (int i = 0; i < STORE_COUNT; i++) {
      stores.put(STORE_KEY_TYPE + i, CONTEXT_TYPE);
    }
    return stores;
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(named("store"), getClass().getName() + "$StoreAdvice");
  }

  public static final class Context {
    public final int value;

    public Context(int value) {
      this.value = value;
    }
  }

  public static final class StoreAdvice {
    @Advice.OnMethodExit
    public static void exit(
        @Advice.Argument(0) int storeIndex, @Advice.Return(readOnly = false) Object store) {
      switch (storeIndex) {
        case 0:
          store = InstrumentationContext.get(STORE_KEY_TYPE + "0", CONTEXT_TYPE);
          break;
        case 1:
          store = InstrumentationContext.get(STORE_KEY_TYPE + "1", CONTEXT_TYPE);
          break;
        case 2:
          store = InstrumentationContext.get(STORE_KEY_TYPE + "2", CONTEXT_TYPE);
          break;
        case 3:
          store = InstrumentationContext.get(STORE_KEY_TYPE + "3", CONTEXT_TYPE);
          break;
        case 4:
          store = InstrumentationContext.get(STORE_KEY_TYPE + "4", CONTEXT_TYPE);
          break;
        case 5:
          store = InstrumentationContext.get(STORE_KEY_TYPE + "5", CONTEXT_TYPE);
          break;
        case 6:
          store = InstrumentationContext.get(STORE_KEY_TYPE + "6", CONTEXT_TYPE);
          break;
        case 7:
          store = InstrumentationContext.get(STORE_KEY_TYPE + "7", CONTEXT_TYPE);
          break;
        case 8:
          store = InstrumentationContext.get(STORE_KEY_TYPE + "8", CONTEXT_TYPE);
          break;
        case 9:
          store = InstrumentationContext.get(STORE_KEY_TYPE + "9", CONTEXT_TYPE);
          break;
        case 10:
          store = InstrumentationContext.get(STORE_KEY_TYPE + "10", CONTEXT_TYPE);
          break;
        case 11:
          store = InstrumentationContext.get(STORE_KEY_TYPE + "11", CONTEXT_TYPE);
          break;
        case 12:
          store = InstrumentationContext.get(STORE_KEY_TYPE + "12", CONTEXT_TYPE);
          break;
        case 13:
          store = InstrumentationContext.get(STORE_KEY_TYPE + "13", CONTEXT_TYPE);
          break;
        case 14:
          store = InstrumentationContext.get(STORE_KEY_TYPE + "14", CONTEXT_TYPE);
          break;
        case 15:
          store = InstrumentationContext.get(STORE_KEY_TYPE + "15", CONTEXT_TYPE);
          break;
        default:
          throw new IllegalArgumentException("unsupported store index: " + storeIndex);
      }
    }
  }

  public static final class Muzzle {
    public static ReferenceMatcher create() {
      return ReferenceMatcher.NO_REFERENCES;
    }
  }
}
