package datadog.trace.instrumentation.pekkohttp.iast.helpers;

import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.instrumentation.pekkohttp.iast.UnmarshallerInstrumentation;
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshaller;
import org.apache.pekko.stream.Materializer;
import scala.Function1;
import scala.PartialFunction;
import scala.concurrent.ExecutionContext;
import scala.concurrent.Future;

/**
 * Marshaller that unconditionally taints its input before passing it to the delegate.
 *
 * @param <A> source type
 * @param <B> target type
 * @see UnmarshallerInstrumentation
 */
public class TaintUnmarshaller<A, B> implements Unmarshaller<A, B> {
  private final PropagationModule propagationModule;
  private final Unmarshaller<A, B> delegate;

  public TaintUnmarshaller(PropagationModule propagationModule, Unmarshaller<A, B> delegate) {
    this.propagationModule = propagationModule;
    this.delegate = delegate;
  }

  @Override
  public Future<B> apply(A value, ExecutionContext ec, Materializer materializer) {
    IastContext ctx = IastContext.Provider.get(AgentTracer.activeSpan());
    if (ctx != null) {
      propagationModule.taintObject(ctx, value, SourceTypes.REQUEST_BODY);
    }
    return delegate.apply(value, ec, materializer);
  }

  @Override
  public <C> Unmarshaller<A, C> transform(
      Function1<ExecutionContext, Function1<Materializer, Function1<Future<B>, Future<C>>>> f) {
    return delegate.transform(f);
  }

  @Override
  public <C> Unmarshaller<A, C> map(Function1<B, C> f) {
    return delegate.map(f);
  }

  @Override
  public <C> Unmarshaller<A, C> flatMap(
      Function1<ExecutionContext, Function1<Materializer, Function1<B, Future<C>>>> f) {
    return delegate.flatMap(f);
  }

  @Override
  public <C> Unmarshaller<A, C> recover(
      Function1<ExecutionContext, Function1<Materializer, PartialFunction<Throwable, C>>> pf) {
    return delegate.recover(pf);
  }

  @Override
  public <BB> Unmarshaller<A, BB> withDefaultValue(BB defaultValue) {
    return delegate.withDefaultValue(defaultValue);
  }

  @Override
  public Unmarshaller<A, B> asScala() {
    return this;
  }
}
