package datadog.trace.instrumentation.akka.concurrent;

import static java.util.Collections.singleton;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import datadog.trace.instrumentation.akka.init.DisableTracingActorInitInstrumentation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@AutoService(InstrumenterModule.class)
public class AkkaConcurrentModule extends InstrumenterModule.Tracing {
  public AkkaConcurrentModule() {
    super("akka_concurrent", "java_concurrent");
  }

  @Override
  public Map<String, String> contextStore() {
    final Map<String, String> store = new HashMap<>();
    store.put(Runnable.class.getName(), State.class.getName());
    store.put("akka.dispatch.Envelope", State.class.getName());
    store.put("akka.dispatch.forkjoin.ForkJoinTask", State.class.getName());
    return store;
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    final List<Instrumenter> instrumenters = new ArrayList<>(8);
    // akka concurrent defaults
    instrumenters.add(new AkkaForkJoinExecutorTaskInstrumentation());
    instrumenters.add(new AkkaForkJoinPoolInstrumentation());
    instrumenters.add(new AkkaForkJoinTaskInstrumentation());
    instrumenters.add(new DisableTracingActorInitInstrumentation());
    final InstrumenterConfig instrumenterConfig = InstrumenterConfig.get();
    // akka actor
    if (instrumenterConfig.isIntegrationEnabled(singleton("akka_actor"), true)) {
      // receive
      if (instrumenterConfig.isIntegrationEnabled(singleton("akka_actor_receive"), true)) {
        instrumenters.add(new AkkaActorCellInstrumentation());
      }
      // send
      if (instrumenterConfig.isIntegrationEnabled(singleton("akka_actor_send"), true)) {
        instrumenters.add(new AkkaEnvelopeInstrumentation());
        instrumenters.add(new AkkaRoutedActorCellInstrumentation());
      }
      // mailbox
      if (instrumenterConfig.isIntegrationEnabled(singleton("akka_actor_mailbox"), true)) {
        instrumenters.add(new AkkaMailboxInstrumentation());
      }
    }
    return instrumenters;
  }
}
