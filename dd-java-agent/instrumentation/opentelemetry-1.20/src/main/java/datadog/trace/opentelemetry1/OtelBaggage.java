package datadog.trace.opentelemetry1;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentScopeContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.core.DDBaggage;
import datadog.trace.core.scopemanager.ScopeContext;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.baggage.BaggageBuilder;
import io.opentelemetry.api.baggage.BaggageEntry;
import io.opentelemetry.context.Scope;
import java.util.Map;
import java.util.function.BiConsumer;
import javax.annotation.Nullable;

public class OtelBaggage implements Baggage {

  final Baggage delegate;

  public static Baggage fromDdBaggage(datadog.trace.api.Baggage baggage) {
    Baggage delegate;
    if (baggage == null || baggage.isEmpty()) {
      delegate = Baggage.empty();
    } else {
      BaggageBuilder builder = Baggage.builder();
      for (Map.Entry<String, String> entry : baggage.asMap().entrySet()) {
        builder.put(entry.getKey(), entry.getValue());
      }
      delegate = builder.build();
    }
    return new OtelBaggage(delegate);
  }

  private OtelBaggage(Baggage delegate) {
    this.delegate = delegate;
  }

  @Override
  public Scope makeCurrent() {
    Scope scope = Baggage.super.makeCurrent();
    AgentScopeContext context =
        ScopeContext.empty().with(toDdBaggage()); // TODO Create a from(Element) method?
    AgentScope agentScope = AgentTracer.get().activateContext(context);
    return new OtelScope(scope, agentScope);
  }

  private datadog.trace.api.Baggage toDdBaggage() {
    if (this.delegate.isEmpty()) {
      return DDBaggage.empty();
    }
    datadog.trace.api.Baggage.BaggageBuilder builder = DDBaggage.builder();
    for (Map.Entry<String, BaggageEntry> entry : this.delegate.asMap().entrySet()) {
      builder.put(entry.getKey(), entry.getValue().getValue());
    }
    return builder.build();
  }

  @Override
  public int size() {
    return this.delegate.size();
  }

  @Override
  public boolean isEmpty() {
    return this.delegate.isEmpty();
  }

  @Override
  public void forEach(BiConsumer<? super String, ? super BaggageEntry> consumer) {
    this.delegate.forEach(consumer);
  }

  @Override
  public Map<String, BaggageEntry> asMap() {
    return this.delegate.asMap();
  }

  @Nullable
  @Override
  public String getEntryValue(String entryKey) {
    return this.delegate.getEntryValue(entryKey);
  }

  @Override
  public BaggageBuilder toBuilder() {
    return this.delegate.toBuilder();
  }
}
