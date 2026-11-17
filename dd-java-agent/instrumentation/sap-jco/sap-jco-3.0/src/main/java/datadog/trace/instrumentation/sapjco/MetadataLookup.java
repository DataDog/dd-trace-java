package datadog.trace.instrumentation.sapjco;

import datadog.context.ContextScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

/**
 * Per-thread link between a {@code JCoRepository.getFunctionTemplate(name)} lookup and the metadata
 * RFC it may trigger. The lookup advice records the requested function name here; the metadata-send
 * advice, which runs only on a cache miss (a real RFC), reads the name and stores the span it
 * opens. The lookup advice then finishes that span, marking it as an error when the function was
 * not found. A cache hit sends no RFC, so no span is ever created.
 */
public final class MetadataLookup {

  private static final ThreadLocal<MetadataLookup> CURRENT = new ThreadLocal<>();

  private final String functionName;
  private AgentSpan span;
  private ContextScope scope;

  private MetadataLookup(String functionName) {
    this.functionName = functionName;
  }

  public static void begin(String functionName) {
    CURRENT.set(new MetadataLookup(functionName));
  }

  public static MetadataLookup current() {
    return CURRENT.get();
  }

  public static void end() {
    CURRENT.remove();
  }

  public String getFunctionName() {
    return functionName;
  }

  public AgentSpan getSpan() {
    return span;
  }

  public void setSpan(AgentSpan span) {
    this.span = span;
  }

  public ContextScope getScope() {
    return scope;
  }

  public void setScope(ContextScope scope) {
    this.scope = scope;
  }
}
