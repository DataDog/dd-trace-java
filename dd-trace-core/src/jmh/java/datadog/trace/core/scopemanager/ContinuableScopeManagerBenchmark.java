package datadog.trace.core.scopemanager;

import com.timgroup.statsd.NoOpStatsDClient;
import datadog.trace.api.DDId;
import datadog.trace.api.interceptor.MutableSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.ScopeSource;
import datadog.trace.core.jfr.DDNoopScopeEventFactory;
import java.util.Collections;
import java.util.Map;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

public class ContinuableScopeManagerBenchmark {

  public enum ConvolutedWork {
    LIGHT {
      @Override
      long work(AgentSpan span) {
        long id = span.getTraceId().toLong();
        id = id ^ Long.reverseBytes(id);
        id = Long.rotateLeft(id, 8);
        id = id ^ Long.reverseBytes(id);
        id = Long.rotateLeft(id, 8);
        id = id ^ Long.reverseBytes(id);
        id = Long.rotateLeft(id, 8);
        id = id ^ Long.reverseBytes(id);
        id = Long.rotateLeft(id, 8);
        id = id ^ Long.reverseBytes(id);
        id = Long.rotateLeft(id, 8);
        id = id ^ Long.reverseBytes(id);
        id = Long.rotateLeft(id, 8);
        id = id ^ Long.reverseBytes(id);
        id = Long.rotateLeft(id, 8);
        id = id ^ Long.reverseBytes(id);
        id = Long.rotateLeft(id, 8);
        id = id ^ Long.reverseBytes(id);
        return id;
      }
    },
    MODERATE {
      @Override
      long work(AgentSpan span) {
        long id = span.getTraceId().toLong();
        id = id ^ Long.reverseBytes(id);
        id = Long.rotateLeft(id, 8);
        id = id ^ Long.reverseBytes(id);
        id = Long.rotateLeft(id, 8);
        id = id ^ Long.reverseBytes(id);
        id = Long.rotateLeft(id, 8);
        id = id ^ Long.reverseBytes(id);
        id = Long.rotateLeft(id, 8);
        id = id ^ Long.reverseBytes(id);
        id = Long.rotateLeft(id, 8);
        id = id ^ Long.reverseBytes(id);
        id = Long.rotateLeft(id, 8);
        id = id ^ Long.reverseBytes(id);
        id = Long.rotateLeft(id, 8);
        id = id ^ Long.reverseBytes(id);
        id = Long.rotateLeft(id, 8);
        id = id ^ Long.reverseBytes(id);

        id = id ^ Long.reverseBytes(id);
        id = Long.rotateRight(id, 4);
        id = id ^ Long.reverseBytes(id);
        id = Long.rotateRight(id, 4);
        id = id ^ Long.reverseBytes(id);
        id = Long.rotateRight(id, 4);
        id = id ^ Long.reverseBytes(id);
        id = Long.rotateRight(id, 4);
        id = id ^ Long.reverseBytes(id);
        id = Long.rotateRight(id, 4);
        id = id ^ Long.reverseBytes(id);
        id = Long.rotateRight(id, 4);
        id = id ^ Long.reverseBytes(id);
        id = Long.rotateRight(id, 4);
        id = id ^ Long.reverseBytes(id);
        id = Long.rotateRight(id, 4);
        id = id ^ Long.reverseBytes(id);
        return id;
      }
    };

    abstract long work(AgentSpan span);
  }

  public static class CheapSpan implements AgentSpan {

    private final DDId traceId = DDId.generate();
    private final long now = System.currentTimeMillis();

    @Override
    public DDId getTraceId() {
      return traceId;
    }

    @Override
    public AgentSpan setTag(String key, boolean value) {
      return this;
    }

    @Override
    public MutableSpan setTag(String tag, Number value) {
      return this;
    }

    @Override
    public Boolean isError() {
      return false;
    }

    @Override
    public AgentSpan setTag(String key, int value) {
      return this;
    }

    @Override
    public AgentSpan setTag(String key, long value) {
      return this;
    }

    @Override
    public AgentSpan setTag(String key, double value) {
      return this;
    }

    @Override
    public long getStartTime() {
      return now;
    }

    @Override
    public long getDurationNano() {
      return 100;
    }

    @Override
    public String getOperationName() {
      return "operation name";
    }

    @Override
    public MutableSpan setOperationName(String serviceName) {
      return this;
    }

    @Override
    public String getServiceName() {
      return "service name";
    }

    @Override
    public MutableSpan setServiceName(String serviceName) {
      return this;
    }

    @Override
    public CharSequence getResourceName() {
      return "resource name";
    }

    @Override
    public MutableSpan setResourceName(CharSequence resourceName) {
      return this;
    }

    @Override
    public Integer getSamplingPriority() {
      return 1;
    }

    @Override
    public MutableSpan setSamplingPriority(int newPriority) {
      return this;
    }

    @Override
    public String getSpanType() {
      return "span type";
    }

    @Override
    public MutableSpan setSpanType(String type) {
      return this;
    }

    @Override
    public Map<String, Object> getTags() {
      return Collections.emptyMap();
    }

    @Override
    public AgentSpan setTag(String key, String value) {
      return this;
    }

    @Override
    public AgentSpan setTag(String key, Object value) {
      return this;
    }

    @Override
    public Object getTag(String key) {
      return null;
    }

    @Override
    public AgentSpan setError(boolean error) {
      return this;
    }

    @Override
    public MutableSpan getRootSpan() {
      return this;
    }

    @Override
    public AgentSpan setErrorMessage(String errorMessage) {
      return this;
    }

    @Override
    public AgentSpan addThrowable(Throwable throwable) {
      return this;
    }

    @Override
    public AgentSpan getLocalRootSpan() {
      return this;
    }

    @Override
    public boolean isSameTrace(AgentSpan otherSpan) {
      return otherSpan == this;
    }

    @Override
    public Context context() {
      return AgentTracer.NoopContext.INSTANCE;
    }

    @Override
    public String getBaggageItem(String key) {
      return null;
    }

    @Override
    public AgentSpan setBaggageItem(String key, String value) {
      return this;
    }

    @Override
    public void finish() {}

    @Override
    public void finish(long finishMicros) {}

    @Override
    public String getSpanName() {
      return "span name";
    }

    @Override
    public void setSpanName(String spanName) {}

    @Override
    public boolean hasResourceName() {
      return false;
    }
  }

  @State(Scope.Benchmark)
  public static class SpanState {

    @Param({"0", "100"})
    int maxDepth;

    @Param({"1", "3", "5"})
    int depth;

    @Param({"true", "false"})
    boolean strict;

    @Param
    ConvolutedWork work;

    private ContinuableScopeManager scopeManager;

    AgentSpan[] spans;

    AgentScope[] scopes;

    @Setup(Level.Trial)
    public void init() {
      scopes = new AgentScope[depth];
      spans = new AgentSpan[depth];
      for (int i = 0; i < spans.length; ++i) {
        spans[i] = new CheapSpan();
      }
      scopeManager =
          new ContinuableScopeManager(
              100, new DDNoopScopeEventFactory(), new NoOpStatsDClient(), strict);
    }
  }

  @Benchmark
  public void activate(SpanState state, Blackhole bh) {
    AgentScope[] scopes = state.scopes;
    AgentSpan[] spans = state.spans;
    for (int i = 0; i < spans.length; ++i) {
      AgentScope scope = state.scopeManager.activate(spans[i], ScopeSource.INSTRUMENTATION);
      scopes[i] = scope;
      bh.consume(state.work.work(spans[i]));
    }
    for (int i = 0; i < spans.length; ++i) {
      scopes[i].close();
      scopes[i] = null;
    }
  }

  @Benchmark
  public void baseline(SpanState state, Blackhole bh) {
    AgentSpan[] spans = state.spans;
    for (int i = 0; i < spans.length; ++i) {
      bh.consume(state.work.work(spans[i]));
    }
  }
}
