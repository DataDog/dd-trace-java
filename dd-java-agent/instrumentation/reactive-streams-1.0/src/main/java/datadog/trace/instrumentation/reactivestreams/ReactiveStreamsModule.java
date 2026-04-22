package datadog.trace.instrumentation.reactivestreams;

import static java.util.Arrays.asList;

import com.google.auto.service.AutoService;
import datadog.context.Context;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@AutoService(InstrumenterModule.class)
public final class ReactiveStreamsModule extends InstrumenterModule.ContextTracking {
  public ReactiveStreamsModule() {
    super("reactive-streams", "reactive-streams-1");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".ReactiveStreamsAsyncResultExtension",
      packageName + ".ReactiveStreamsAsyncResultExtension$WrappedPublisher",
      packageName + ".ReactiveStreamsAsyncResultExtension$WrappedSubscriber",
      packageName + ".ReactiveStreamsAsyncResultExtension$WrappedSubscription",
    };
  }

  @Override
  public Map<String, String> contextStore() {
    final Map<String, String> store = new HashMap<>();
    store.put("org.reactivestreams.Subscriber", Context.class.getName());
    store.put("org.reactivestreams.Publisher", Context.class.getName());
    return store;
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return asList(new PublisherInstrumentation(), new SubscriberInstrumentation());
  }
}
