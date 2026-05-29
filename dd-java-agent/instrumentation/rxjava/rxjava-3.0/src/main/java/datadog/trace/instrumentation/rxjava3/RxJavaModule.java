package datadog.trace.instrumentation.rxjava3;

import static java.util.Arrays.asList;

import com.google.auto.service.AutoService;
import datadog.context.Context;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@AutoService(InstrumenterModule.class)
public final class RxJavaModule extends InstrumenterModule.ContextTracking {
  public RxJavaModule() {
    super("rxjava");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".TracingCompletableObserver",
      packageName + ".TracingSubscriber",
      packageName + ".TracingMaybeObserver",
      packageName + ".TracingObserver",
      packageName + ".RxJavaAsyncResultExtension",
      packageName + ".TracingSingleObserver",
    };
  }

  @Override
  public Map<String, String> contextStore() {
    final Map<String, String> store = new HashMap<>();
    store.put("io.reactivex.rxjava3.core.Flowable", Context.class.getName());
    store.put("io.reactivex.rxjava3.core.Completable", Context.class.getName());
    store.put("io.reactivex.rxjava3.core.Maybe", Context.class.getName());
    store.put("io.reactivex.rxjava3.core.Observable", Context.class.getName());
    store.put("io.reactivex.rxjava3.core.Single", Context.class.getName());
    return store;
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return asList(
        new CompletableInstrumentation(),
        new FlowableInstrumentation(),
        new MaybeInstrumentation(),
        new ObservableInstrumentation(),
        new SingleInstrumentation());
  }
}
