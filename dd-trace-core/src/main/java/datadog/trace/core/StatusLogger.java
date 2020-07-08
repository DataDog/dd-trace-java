package datadog.trace.core;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.net.Socket;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class StatusLogger {

  public static void logStatus(Config config) {
    log.info(
        new Moshi.Builder()
            .add(ConfigAdapter.FACTORY)
            .build()
            .adapter(Config.class)
            .toJson(config));
  }

  private static boolean agentServiceCheck(Config config) {
    try (Socket s = new Socket(config.getAgentHost(), config.getAgentPort())) {
      return true;
    } catch (IOException ex) {
      return false;
    }
  }

  private static class ConfigAdapter extends JsonAdapter<Config> {

    public static final JsonAdapter.Factory FACTORY =
        new JsonAdapter.Factory() {

          @Override
          public JsonAdapter<?> create(
              Type type, Set<? extends Annotation> annotations, Moshi moshi) {
            final Class<?> rawType = Types.getRawType(type);
            if (rawType.isAssignableFrom(Config.class)) {
              return new ConfigAdapter();
            }
            return null;
          }
        };

    @Override
    public Config fromJson(JsonReader reader) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void toJson(JsonWriter writer, Config config) throws IOException {
      writer.beginObject();
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
      writer.name("enabled");
      writer.value(config.isTraceEnabled());
      writer.name("service");
      writer.value(config.getServiceName());
      writer.name("agent_url");
      writer.value("http://" + config.getAgentHost() + ":" + config.getAgentPort());
      writer.name("agent_error");
      writer.value(!agentServiceCheck(config));
      writer.name("debug");
      writer.value(config.isDebugEnabled());
      writer.name("analytics_enabled");
      writer.value(config.isTraceAnalyticsEnabled());
      writer.name("sample_rate");
      writer.value(config.getTraceSampleRate());
      writer.name("sampling_rules");
      writer.beginArray();
      writeMap(writer, config.getTraceSamplingServiceRules());
      writeMap(writer, config.getTraceSamplingOperationRules());
      writer.endArray();
      writer.name("priority_sampling_enabled");
      writer.value(config.isPrioritySamplingEnabled());
      writer.name("logs_correlation_enabled");
      writer.value(config.isLogsInjectionEnabled());
      writer.name("profiling_enabled");
      writer.value(config.isProfilingEnabled());
      writer.name("dd_version");
      writer.value(String.valueOf(config.getMergedSpanTags().get(Tags.DD_VERSION)));
      writer.name("health_checks_enabled");
      writer.value(config.isHealthMetricsEnabled());
      writer.name("configuration_file");
      writer.value(config.getConfigFile());
      writer.name("runtime_id");
      writer.value(config.getRuntimeId());
      writer.endObject();
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
}
