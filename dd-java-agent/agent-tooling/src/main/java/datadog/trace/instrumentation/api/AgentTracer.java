package datadog.trace.instrumentation.api;

import static io.opentracing.log.Fields.ERROR_OBJECT;
import static io.opentracing.propagation.Format.Builtin.TEXT_MAP;
import static java.util.Collections.singletonMap;

import datadog.trace.agent.decorator.BaseDecorator;
import datadog.trace.context.TraceScope;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.opentracing.propagation.TextMap;
import io.opentracing.tag.Tags;
import io.opentracing.util.GlobalTracer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public abstract class AgentTracer {
  // Implicit parent
  public static AgentSpan startSpan(final BaseDecorator decorator) {
    return get().startSpan(decorator);
  }

  // Explicit parent
  public static AgentSpan startSpan(final BaseDecorator decorator, final AgentSpan.Context parent) {
    return get().startSpan(decorator, parent);
  }

  public static AgentScope activateSpan(final AgentSpan span) {
    return get().activateSpan(span);
  }

  public static AgentSpan activeSpan() {
    return get().activeSpan();
  }

  public static Propagation propagate() {
    return get().propagate();
  }

  private static final TracerAPI DEFAULT = new OpenTracing31();
  private static final AtomicReference<TracerAPI> provider = new AtomicReference<>(DEFAULT);

  public static void registerIfAbsent(final TracerAPI trace) {
    provider.compareAndSet(DEFAULT, trace);
  }

  public static TracerAPI get() {
    return provider.get();
  }

  public interface TracerAPI {
    AgentSpan startSpan(BaseDecorator decorator);

    AgentSpan startSpan(BaseDecorator decorator, AgentSpan.Context parent);

    AgentScope activateSpan(AgentSpan span);

    AgentSpan activeSpan();

    Propagation propagate();
  }

  private static final class OpenTracing31 implements TracerAPI {
    private final Tracer tracer = GlobalTracer.get();
    private final OT31Propagation propagation = new OT31Propagation();

    @Override
    public AgentSpan startSpan(final BaseDecorator decorator) {
      return new OT31Span(decorator);
    }

    @Override
    public AgentSpan startSpan(final BaseDecorator decorator, final AgentSpan.Context parent) {
      return new OT31Span(decorator, parent);
    }

    @Override
    public AgentScope activateSpan(final AgentSpan span) {
      return new OT31Scope(span);
    }

    @Override
    public AgentSpan activeSpan() {
      final Span span = tracer.activeSpan();
      if (span instanceof AgentSpan) {
        return (AgentSpan) span;
      } else {
        return new OT31WrappedSpan(span);
      }
    }

    @Override
    public Propagation propagate() {
      return propagation;
    }

    private final class OT31Span extends OT31WrappedSpan implements AgentSpan, Span {
      private final BaseDecorator decorator;

      public OT31Span(final BaseDecorator decorator) {
        super(tracer.buildSpan(decorator.spanName()).start());
        decorator.afterStart(this);
        this.decorator = decorator;
      }

      public OT31Span(final BaseDecorator decorator, final Context parent) {
        super(
            tracer
                .buildSpan(decorator.spanName())
                .ignoreActiveSpan()
                .asChildOf(((OT31Propagation.OTContext) parent).context)
                .start());
        decorator.afterStart(this);
        this.decorator = decorator;
      }

      @Override
      public SpanContext context() {
        return span.context();
      }

      @Override
      public Span setTag(final String key, final String value) {
        return span.setTag(key, value);
      }

      @Override
      public Span setTag(final String key, final boolean value) {
        return span.setTag(key, value);
      }

      @Override
      public Span setTag(final String key, final Number value) {
        return span.setTag(key, value);
      }

      @Override
      public Span log(final Map<String, ?> fields) {
        return span.log(fields);
      }

      @Override
      public Span log(final long timestampMicroseconds, final Map<String, ?> fields) {
        return span.log(timestampMicroseconds, fields);
      }

      @Override
      public Span log(final String event) {
        return span.log(event);
      }

      @Override
      public Span log(final long timestampMicroseconds, final String event) {
        return span.log(timestampMicroseconds, event);
      }

      @Override
      public Span setBaggageItem(final String key, final String value) {
        return span.setBaggageItem(key, value);
      }

      @Override
      public String getBaggageItem(final String key) {
        return span.getBaggageItem(key);
      }

      @Override
      public Span setOperationName(final String operationName) {
        return span.setOperationName(operationName);
      }

      @Override
      public void finish() {
        decorator.beforeFinish(this);
        span.finish();
      }

      @Override
      public void finish(final long finishMicros) {
        decorator.beforeFinish(this);
        span.finish(finishMicros);
      }
    }

    private class OT31WrappedSpan implements AgentSpan {

      protected final Span span;

      public OT31WrappedSpan(final Span span) {
        this.span = span;
      }

      @Override
      public AgentSpan setMetadata(final String key, final boolean value) {
        span.setTag(key, value);
        return this;
      }

      @Override
      public AgentSpan setMetadata(final String key, final int value) {
        span.setTag(key, value);
        return this;
      }

      @Override
      public AgentSpan setMetadata(final String key, final long value) {
        span.setTag(key, value);
        return this;
      }

      @Override
      public AgentSpan setMetadata(final String key, final double value) {
        span.setTag(key, value);
        return this;
      }

      @Override
      public AgentSpan setMetadata(final String key, final String value) {
        span.setTag(key, value);
        return this;
      }

      @Override
      public AgentSpan setError(final boolean error) {
        Tags.ERROR.set(span, error);
        return this;
      }

      @Override
      public AgentSpan addThrowable(final Throwable throwable) {
        span.log(singletonMap(ERROR_OBJECT, throwable));
        return this;
      }

      @Override
      public void finish() {
        span.finish();
      }
    }

    private final class OT31Scope implements AgentScope {

      private final TraceScope scope;
      private final OT31Span span;

      public OT31Scope(final AgentSpan span) {
        assert span instanceof OT31Span;
        this.span = (OT31Span) span;
        scope = (TraceScope) tracer.scopeManager().activate((OT31Span) span, false);
      }

      @Override
      public Continuation capture() {
        return scope.capture();
      }

      @Override
      public void close() {
        scope.close();
      }

      @Override
      public boolean isAsyncPropagating() {
        return scope.isAsyncPropagating();
      }

      @Override
      public void setAsyncPropagation(final boolean value) {
        scope.setAsyncPropagation(value);
      }

      @Override
      public AgentSpan span() {
        return span;
      }
    }

    private final class OT31Propagation implements Propagation {

      @Override
      public TraceScope.Continuation capture() {
        final Scope active = tracer.scopeManager().active();
        if (active instanceof TraceScope) {
          return ((TraceScope) active).capture();
        } else {
          return null;
        }
      }

      @Override
      public <C> void inject(final AgentSpan span, final C carrier, final Setter<C> setter) {
        assert span instanceof OT31Span;
        tracer.inject(((OT31Span) span).context(), TEXT_MAP, new Injector<>(carrier, setter));
      }

      private final class Injector<C> implements TextMap {
        private final C carrier;
        private final Setter<C> setter;

        public Injector(final C carrier, final Setter<C> setter) {
          this.carrier = carrier;
          this.setter = setter;
        }

        @Override
        public Iterator<Map.Entry<String, String>> iterator() {
          throw new UnsupportedOperationException();
        }

        @Override
        public void put(final String key, final String value) {
          setter.set(carrier, key, value);
        }
      }

      @Override
      public <C> AgentSpan.Context extract(final C carrier, final Getter<C> getter) {
        return new OTContext(tracer.extract(TEXT_MAP, new Extractor(carrier, getter)));
      }

      private final class Extractor<C> implements TextMap {
        private final Map<String, String> extracted;

        public Extractor(final C carrier, final Getter<C> getter) {
          extracted = new HashMap<>();
          for (final String key : getter.keys(carrier)) {
            extracted.put(key, getter.get(carrier, key));
          }
        }

        @Override
        public Iterator<Map.Entry<String, String>> iterator() {
          return extracted.entrySet().iterator();
        }

        @Override
        public void put(final String key, final String value) {
          throw new UnsupportedOperationException();
        }
      }

      private final class OTContext implements AgentSpan.Context, SpanContext {
        private final SpanContext context;

        public OTContext(final SpanContext context) {
          this.context = context;
        }

        @Override
        public Iterable<Map.Entry<String, String>> baggageItems() {
          return context.baggageItems();
        }
      }
    }
  }
}
