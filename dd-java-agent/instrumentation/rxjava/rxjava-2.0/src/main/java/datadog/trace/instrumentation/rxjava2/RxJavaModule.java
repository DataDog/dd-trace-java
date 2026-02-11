package datadog.trace.instrumentation.rxjava2;

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
    store.put("io.reactivex.Flowable", Context.class.getName());
    store.put("io.reactivex.Completable", Context.class.getName());
    store.put("io.reactivex.Maybe", Context.class.getName());
    store.put("io.reactivex.Observable", Context.class.getName());
    store.put("io.reactivex.Single", Context.class.getName());
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
