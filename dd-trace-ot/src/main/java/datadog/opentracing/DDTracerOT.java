package datadog.opentracing;

import datadog.trace.core.DDSpan;
import datadog.trace.core.DDSpanContext;
import datadog.trace.core.DDTracer;
import datadog.trace.core.DDTracer.DDSpanBuilder;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.common.sampling.Sampler;
import datadog.trace.common.writer.Writer;
import datadog.trace.context.TraceScope;
import datadog.trace.core.propagation.ExtractedContext;
import datadog.trace.core.propagation.HttpCodec;
import datadog.trace.core.propagation.TagContext;
import datadog.trace.core.scopemanager.DDScopeManager;
import io.opentracing.References;
import io.opentracing.Scope;
import io.opentracing.ScopeManager;
import io.opentracing.Tracer;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DDTracerOT implements Tracer {
  private final DDTracer coreTracer;

  public static class DDTracerOTBuilder {
    public DDTracerOTBuilder() {
      // Apply the default values from config.
      config(Config.get());
    }

    public DDTracerOTBuilder withProperties(final Properties properties) {
      return config(Config.get(properties));
    }
  }

  @Deprecated
  public DDTracerOT() {
    coreTracer = DDTracer.builder().build();
  }

  @Deprecated
  public DDTracerOT(final String serviceName) {
    coreTracer = DDTracer.builder().serviceName(serviceName).build();
  }

  @Deprecated
  public DDTracerOT(final Properties properties) {
    coreTracer = DDTracer.builder().withProperties(properties).build();
  }

  @Deprecated
  public DDTracerOT(final Config config) {
    coreTracer = DDTracer.builder().config(config).build();
  }

  // This constructor is already used in the wild, so we have to keep it inside this API for now.
  @Deprecated
  public DDTracerOT(final String serviceName, final Writer writer, final Sampler sampler) {
    coreTracer =
        DDTracer.builder().serviceName(serviceName).writer(writer).sampler(sampler).build();
  }

  @Deprecated
  DDTracerOT(
      final String serviceName,
      final Writer writer,
      final Sampler sampler,
      final Map<String, String> runtimeTags) {
    coreTracer =
        DDTracer.builder()
            .serviceName(serviceName)
            .writer(writer)
            .sampler(sampler)
            .localRootSpanTags(runtimeTags)
            .build();
  }

  @Deprecated
  public DDTracerOT(final Writer writer) {
    coreTracer = DDTracer.builder().writer(writer).build();
  }

  @Deprecated
  public DDTracerOT(final Config config, final Writer writer) {
    coreTracer = DDTracer.builder().config(config).writer(writer).build();
  }

  @Deprecated
  public DDTracerOT(
      final String serviceName,
      final Writer writer,
      final Sampler sampler,
      final String runtimeId,
      final Map<String, String> localRootSpanTags,
      final Map<String, String> defaultSpanTags,
      final Map<String, String> serviceNameMappings,
      final Map<String, String> taggedHeaders) {
    coreTracer =
        DDTracer.builder()
            .serviceName(serviceName)
            .writer(writer)
            .sampler(sampler)
            .localRootSpanTags(customRuntimeTags(runtimeId, localRootSpanTags))
            .defaultSpanTags(defaultSpanTags)
            .serviceNameMappings(serviceNameMappings)
            .taggedHeaders(taggedHeaders)
            .build();
  }

  @Deprecated
  public DDTracerOT(
      final String serviceName,
      final Writer writer,
      final Sampler sampler,
      final Map<String, String> localRootSpanTags,
      final Map<String, String> defaultSpanTags,
      final Map<String, String> serviceNameMappings,
      final Map<String, String> taggedHeaders) {

    coreTracer =
        DDTracer.builder()
            .serviceName(serviceName)
            .writer(writer)
            .sampler(sampler)
            .localRootSpanTags(localRootSpanTags)
            .defaultSpanTags(defaultSpanTags)
            .serviceNameMappings(serviceNameMappings)
            .taggedHeaders(taggedHeaders)
            .build();
  }

  @Deprecated
  public DDTracerOT(
      final String serviceName,
      final Writer writer,
      final Sampler sampler,
      final Map<String, String> localRootSpanTags,
      final Map<String, String> defaultSpanTags,
      final Map<String, String> serviceNameMappings,
      final Map<String, String> taggedHeaders,
      final int partialFlushMinSpans) {

    coreTracer =
        DDTracer.builder()
            .serviceName(serviceName)
            .writer(writer)
            .sampler(sampler)
            .localRootSpanTags(localRootSpanTags)
            .defaultSpanTags(defaultSpanTags)
            .serviceNameMappings(serviceNameMappings)
            .taggedHeaders(taggedHeaders)
            .partialFlushMinSpans(partialFlushMinSpans)
            .build();
  }

  @Builder
  // These field names must be stable to ensure the builder api is stable.
  private DDTracerOT(
      final Config config,
      final String serviceName,
      final Writer writer,
      final Sampler sampler,
      final HttpCodec.Injector injector,
      final HttpCodec.Extractor extractor,
      final ScopeManager scopeManager,
      final Map<String, String> localRootSpanTags,
      final Map<String, String> defaultSpanTags,
      final Map<String, String> serviceNameMappings,
      final Map<String, String> taggedHeaders,
      final int partialFlushMinSpans) {

    final DDScopeManager ddScopeManager =
        scopeManager == null ? null : new ScopeManagerWrapper(scopeManager);
    coreTracer =
        DDTracer.builder()
            .config(config)
            .serviceName(serviceName)
            .writer(writer)
            .sampler(sampler)
            .injector(injector)
            .extractor(extractor)
            .scopeManager(ddScopeManager)
            .localRootSpanTags(localRootSpanTags)
            .defaultSpanTags(defaultSpanTags)
            .serviceNameMappings(serviceNameMappings)
            .taggedHeaders(taggedHeaders)
            .partialFlushMinSpans(partialFlushMinSpans)
            .build();
  }

  private static Map<String, String> customRuntimeTags(
      final String runtimeId, final Map<String, String> applicationRootSpanTags) {
    final Map<String, String> runtimeTags = new HashMap<>(applicationRootSpanTags);
    runtimeTags.put(Config.RUNTIME_ID_TAG, runtimeId);
    return Collections.unmodifiableMap(runtimeTags);
  }

  /** Allows custom scope managers to be passed in to constructor */
  private class ScopeManagerWrapper implements DDScopeManager {
    private final ScopeManager scopeManager;

    private ScopeManagerWrapper(final ScopeManager scopeManager) {
      this.scopeManager = scopeManager;
    }

    @Override
    public AgentScope activate(final AgentSpan span, final boolean finishOnClose) {
      // TODO Autogenerated method stub
      return null;
    }

    @Override
    public AgentScope active() {
      // TODO Autogenerated method stub
      return null;
    }

    @Override
    public AgentSpan activeSpan() {
      // TODO Autogenerated method stub
      return null;
    }
  }

}
