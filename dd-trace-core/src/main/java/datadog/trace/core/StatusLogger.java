package datadog.trace.core;

import static datadog.trace.api.Config.isDatadogProfilerEnablementOverridden;
import static datadog.trace.api.Config.isDatadogProfilerSafeInCurrentEnvironment;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;
import com.squareup.moshi.Moshi;
import datadog.trace.api.Config;
import datadog.trace.api.ProductActivation;
import datadog.trace.logging.LoggingSettingsDescription;
import datadog.trace.util.AgentTaskScheduler;
import java.io.File;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class StatusLogger extends JsonAdapter<Config>
    implements AgentTaskScheduler.Task<Config>, JsonAdapter.Factory {

  public static void logStatus(Config config) {
    AgentTaskScheduler.get().schedule(new StatusLogger(), config, 500, MILLISECONDS);
  }

  @Override
  public void run(Config config) {
    Logger log = LoggerFactory.getLogger(StatusLogger.class);
    if (log.isInfoEnabled()) {
      log.info(
          "DATADOG TRACER CONFIGURATION {}",
          new Moshi.Builder().add(this).build().adapter(Config.class).toJson(config));
    }
    if (log.isDebugEnabled()) {
      log.debug("class path: {}", System.getProperty("java.class.path"));
    }
  }

  @Override
  public Config fromJson(JsonReader reader) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void toJson(JsonWriter writer, Config config) throws IOException {
    if (null == config) {
      return;
    }
    writer.beginObject();
    writer.name("version");
    writer.value(DDTraceCoreInfo.VERSION);
    writer.name("os_name");
    writer.value(System.getProperty("os.name"));
    writer.name("os_version");
    writer.value(System.getProperty("os.version"));
    writer.name("architecture");
    writer.value(System.getProperty("os.arch"));
    writer.name("lang");
    writer.value("jvm");
    writer.name("lang_version");
    writer.value(System.getProperty("java.version"));
    writer.name("jvm_vendor");
    writer.value(System.getProperty("java.vendor"));
    writer.name("jvm_version");
    writer.value(System.getProperty("java.vm.version"));
    writer.name("java_class_version");
    writer.value(System.getProperty("java.class.version"));
    writer.name("http_nonProxyHosts");
    writer.value(String.valueOf(System.getProperty("http.nonProxyHosts")));
    writer.name("http_proxyHost");
    writer.value(String.valueOf(System.getProperty("http.proxyHost")));
    writer.name("enabled");
    writer.value(config.isTraceEnabled());
    writer.name("service");
    writer.value(config.getServiceName());
    writer.name("agent_url");
    writer.value(config.getAgentUrl());
    writer.name("agent_unix_domain_socket");
    writer.value(config.getAgentUnixDomainSocket());
    writer.name("agent_named_pipe");
    writer.value(config.getAgentNamedPipe());
    writer.name("agent_error");
    writer.value(!agentServiceCheck(config));
    writer.name("debug");
    writer.value(config.isDebugEnabled());
    writer.name("trace_propagation_style_extract");
    writer.beginArray();
    writeSet(writer, config.getTracePropagationStylesToExtract());
    writer.endArray();
    writer.name("trace_propagation_style_inject");
    writer.beginArray();
    writeSet(writer, config.getTracePropagationStylesToInject());
    writer.endArray();
    writer.name("analytics_enabled");
    writer.value(config.isTraceAnalyticsEnabled());
    writer.name("sample_rate");
    writer.value(config.getTraceSampleRate());
    writer.name("priority_sampling_enabled");
    writer.value(config.isPrioritySamplingEnabled());
    writer.name("logs_correlation_enabled");
    writer.value(config.isLogsInjectionEnabled());
    writer.name("profiling_enabled");
    writer.value(config.isProfilingEnabled());
    writer.name("remote_config_enabled");
    writer.value(config.isRemoteConfigEnabled());
    writer.name("debugger_enabled");
    writer.value(config.isDynamicInstrumentationEnabled());
    writer.name("debugger_exception_enabled");
    writer.value(config.isDebuggerExceptionEnabled());
    writer.name("debugger_span_origin_enabled");
    writer.value(config.isDebuggerCodeOriginEnabled());
    writer.name("debugger_distributed_debugger_enabled");
    writer.value(config.isDistributedDebuggerEnabled());
    writer.name("appsec_enabled");
    writer.value(config.getAppSecActivation().toString());
    writer.name("appsec_rules_file_path");
    writer.value(config.getAppSecRulesFile());
    writer.name("rasp_enabled");
    writer.value(config.isAppSecRaspEnabled());
    writer.name("telemetry_enabled");
    writer.value(config.isTelemetryEnabled());
    writer.name("telemetry_dependency_collection_enabled");
    writer.value(config.isTelemetryDependencyServiceEnabled());
    writer.name("telemetry_log_collection_enabled");
    writer.value(config.isTelemetryLogCollectionEnabled());
    writer.name("dd_version");
    writer.value(config.getVersion());
    writer.name("health_checks_enabled");
    writer.value(config.isHealthMetricsEnabled());
    writer.name("configuration_file");
    writer.value(config.getConfigFileStatus());
    writer.name("runtime_id");
    writer.value(config.getRuntimeId());
    writer.name("logging_settings");
    writeObjectMap(writer, LoggingSettingsDescription.getDescription());
    writer.name("cws_enabled");
    writer.value(config.isCwsEnabled());
    writer.name("cws_tls_refresh");
    writer.value(config.getCwsTlsRefresh());
    writer.name("datadog_profiler_enabled");
    writer.value(config.isDatadogProfilerEnabled());
    writer.name("datadog_profiler_safe");
    writer.value(isDatadogProfilerSafeInCurrentEnvironment());
    writer.name("datadog_profiler_enabled_overridden");
    writer.value(isDatadogProfilerEnablementOverridden());
    if (config.getIastActivation() != ProductActivation.FULLY_DISABLED) {
      writer.name("iast_enabled");
      writer.value(config.getIastActivation().toString());
    }
    writer.name("data_streams_enabled");
    writer.value(config.isDataStreamsEnabled());
    writer.name("data_streams_transaction_extractors");
    writer.value(config.getDataStreamsTransactionExtractors());

    writer.name("app_logs_collection_enabled");
    writer.value(config.isAppLogsCollectionEnabled());
    writer.endObject();
  }

  private static boolean agentServiceCheck(Config config) {
    if (config.getAgentUrl().startsWith("unix:")) {
      return new File(config.getAgentUnixDomainSocket()).exists();
    } else {
      try (Socket s = new Socket()) {
        s.connect(new InetSocketAddress(config.getAgentHost(), config.getAgentPort()), 500);
        return true;
      } catch (IOException ex) {
        return false;
      }
    }
  }

  private static void writeMap(JsonWriter writer, Map<String, String> map) throws IOException {
    writer.beginObject();
    for (Map.Entry<String, String> entry : map.entrySet()) {
      writer.name(entry.getKey());
      writer.value(entry.getValue());
    }
    writer.endObject();
  }

  private static void writeObjectMap(JsonWriter writer, Map<String, Object> map)
      throws IOException {
    writer.beginObject();
    for (Map.Entry<String, Object> entry : map.entrySet()) {
      writer.name(entry.getKey());
      Object value = entry.getValue();
      if (value instanceof Number) {
        writer.value((Number) value);
      } else if (value instanceof Boolean) {
        writer.value((Boolean) value);
      } else {
        writer.value(String.valueOf(value));
      }
    }
    writer.endObject();
  }

  private static void writeSet(JsonWriter writer, Set<?> set) throws IOException {
    for (Object o : set) {
      writer.value(o.toString());
    }
  }

  @Override
  public JsonAdapter<?> create(Type type, Set<? extends Annotation> annotations, Moshi moshi) {
    return this;
  }
}
