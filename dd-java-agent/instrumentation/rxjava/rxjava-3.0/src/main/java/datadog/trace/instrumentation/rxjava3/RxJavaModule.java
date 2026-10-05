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
    super("rxjava", "rxjava-3");
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
    String contextClass = Context.class.getName();
    final Map<String, String> store = new HashMap<>();
    store.put("io.reactivex.rxjava3.core.Flowable", contextClass);
    store.put("io.reactivex.rxjava3.core.Completable", contextClass);
    store.put("io.reactivex.rxjava3.core.Maybe", contextClass);
    store.put("io.reactivex.rxjava3.core.Observable", contextClass);
    store.put("io.reactivex.rxjava3.core.Single", contextClass);
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
