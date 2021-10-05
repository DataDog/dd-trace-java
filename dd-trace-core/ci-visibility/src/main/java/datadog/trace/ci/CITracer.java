package datadog.trace.ci;

import datadog.trace.api.Checkpointer;
import datadog.trace.api.PropagationStyle;
import datadog.trace.api.gateway.InstrumentationGateway;
import datadog.trace.api.interceptor.TraceInterceptor;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.ScopeSource;
import datadog.trace.context.ScopeListener;
import datadog.trace.context.TraceScope;
import datadog.trace.core.CoreTracer;

/**
 * This tracer is specific for CI Visibility The CITracer is backed by an instance of the {@code
 * CoreTracer} class configured to be used in the CI Visibility mode.
 */
public class CITracer implements AgentTracer.TracerAPI {

  private final CoreTracer coreTracer;

  CITracer(final CoreTracer coreTracer) {
    this.coreTracer = coreTracer;
  }

  @Override
  public String getTraceId() {
    return coreTracer.getTraceId();
  }

  @Override
  public String getSpanId() {
    return coreTracer.getSpanId();
  }

  @Override
  public boolean addTraceInterceptor(TraceInterceptor traceInterceptor) {
    return coreTracer.addTraceInterceptor(traceInterceptor);
  }

  @Override
  public void addScopeListener(ScopeListener listener) {
    this.coreTracer.addScopeListener(listener);
  }

  @Override
  public void checkpoint(AgentSpan span, int flags) {
    this.coreTracer.checkpoint(span, flags);
  }

  @Override
  public void onStart(AgentSpan span) {
    this.coreTracer.onStart(span);
  }

  @Override
  public void onStartWork(AgentSpan span) {
    this.coreTracer.onStartWork(span);
  }

  @Override
  public void onFinishWork(AgentSpan span) {
    this.coreTracer.onFinishWork(span);
  }

  @Override
  public void onStartThreadMigration(AgentSpan span) {
    this.coreTracer.onStartThreadMigration(span);
  }

  @Override
  public void onFinishThreadMigration(AgentSpan span) {
    this.coreTracer.onFinishThreadMigration(span);
  }

  @Override
  public void onFinish(AgentSpan span) {
    this.coreTracer.onFinish(span);
  }

  @Override
  public void onRootSpan(AgentSpan root, boolean published) {
    this.coreTracer.onRootSpan(root, published);
  }

  @Override
  public TraceScope.Continuation capture() {
    return this.coreTracer.capture();
  }

  @Override
  public <C> void inject(AgentSpan span, C carrier, Setter<C> setter) {
    this.coreTracer.inject(span, carrier, setter);
  }

  @Override
  public <C> void inject(AgentSpan.Context context, C carrier, Setter<C> setter) {
    this.coreTracer.inject(context, carrier, setter);
  }

  @Override
  public <C> void inject(AgentSpan span, C carrier, Setter<C> setter, PropagationStyle style) {
    this.coreTracer.inject(span, carrier, setter, style);
  }

  @Override
  public <C> AgentSpan.Context.Extracted extract(C carrier, ContextVisitor<C> getter) {
    return this.coreTracer.extract(carrier, getter);
  }

  @Override
  public AgentSpan startSpan(CharSequence spanName, boolean emitCheckpoint) {
    return this.coreTracer.startSpan(spanName, emitCheckpoint);
  }

  @Override
  public AgentSpan startSpan(CharSequence spanName, long startTimeMicros, boolean emitCheckpoint) {
    return this.coreTracer.startSpan(spanName, startTimeMicros, emitCheckpoint);
  }

  @Override
  public AgentSpan startSpan(
      CharSequence spanName, AgentSpan.Context parent, boolean emitCheckpoint) {
    return this.coreTracer.startSpan(spanName, parent, emitCheckpoint);
  }

  @Override
  public AgentSpan startSpan(
      CharSequence spanName,
      AgentSpan.Context parent,
      long startTimeMicros,
      boolean emitCheckpoint) {
    return this.coreTracer.startSpan(spanName, parent, startTimeMicros, emitCheckpoint);
  }

  @Override
  public AgentScope activateSpan(AgentSpan span, ScopeSource source) {
    return this.coreTracer.activateSpan(span, source);
  }

  @Override
  public AgentScope activateSpan(AgentSpan span, ScopeSource source, boolean isAsyncPropagating) {
    return this.coreTracer.activateSpan(span, source, isAsyncPropagating);
  }

  @Override
  public TraceScope.Continuation captureSpan(AgentSpan span, ScopeSource source) {
    return this.coreTracer.captureSpan(span, source);
  }

  @Override
  public AgentSpan activeSpan() {
    return this.coreTracer.activeSpan();
  }

  @Override
  public TraceScope activeScope() {
    return this.coreTracer.activeScope();
  }

  @Override
  public AgentPropagation propagate() {
    return this.coreTracer.propagate();
  }

  @Override
  public AgentSpan noopSpan() {
    return this.coreTracer.noopSpan();
  }

  @Override
  public AgentTracer.SpanBuilder buildSpan(CharSequence spanName) {
    return this.coreTracer.buildSpan(spanName);
  }

  @Override
  public void close() {
    this.coreTracer.close();
  }

  @Override
  public void flush() {
    this.coreTracer.flush();
  }

  @Override
  public void registerCheckpointer(Checkpointer checkpointer) {
    this.coreTracer.registerCheckpointer(checkpointer);
  }

  @Override
  public InstrumentationGateway instrumentationGateway() {
    return this.coreTracer.instrumentationGateway();
  }
}
