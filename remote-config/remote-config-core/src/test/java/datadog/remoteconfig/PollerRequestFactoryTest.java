package datadog.remoteconfig;

import static datadog.trace.api.config.GeneralConfig.EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED;
import static datadog.trace.junit.utils.config.WithConfigExtension.injectSysConfig;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.squareup.moshi.Moshi;
import datadog.remoteconfig.tuf.RemoteConfigRequest;
import datadog.trace.api.Config;
import datadog.trace.api.ProcessTags;
import datadog.trace.api.remoteconfig.ServiceNameCollector;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.junit.utils.config.WithConfig;
import datadog.trace.test.util.DDJavaSpecification;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PollerRequestFactoryTest extends DDJavaSpecification {

  static final String TRACER_VERSION = "v1.2.3";
  static final String CONTAINER_ID = "456";
  static final String ENTITY_ID = "32423";
  static final String INVALID_REMOTE_CONFIG_URL = "https://invalid.example.com/";

  @Test
  @WithConfig(key = "service", value = "Service Name")
  @WithConfig(key = "env", value = "PROD")
  @WithConfig(key = "tags", value = "version:1.0.0-SNAPSHOT")
  @WithConfig(
      key = "trace.global.tags",
      value =
          Tags.GIT_REPOSITORY_URL
              + ":https://github.com/DataDog/dd-trace-java,"
              + Tags.GIT_COMMIT_SHA
              + ":1234")
  void remoteConfigRequestFieldsBeenSanitized() {
    PollerRequestFactory factory =
        new PollerRequestFactory(
            Config.get(), TRACER_VERSION, CONTAINER_ID, ENTITY_ID, INVALID_REMOTE_CONFIG_URL, null);

    RemoteConfigRequest request =
        factory.buildRemoteConfigRequest(
            Collections.singletonList("ASM"), null, null, 0, ServiceNameCollector.get());

    RemoteConfigRequest.ClientInfo.TracerInfo tracerInfo = request.getClient().getTracerInfo();
    assertEquals("service_name", tracerInfo.getServiceName());
    assertEquals("prod", tracerInfo.getServiceEnv());
    assertEquals("1.0.0-snapshot", tracerInfo.getServiceVersion());
    assertTrue(tracerInfo.getTags().contains("env:PROD"));
    assertTrue(
        tracerInfo
            .getTags()
            .contains(Tags.GIT_REPOSITORY_URL + ":https://github.com/DataDog/dd-trace-java"));
    assertTrue(tracerInfo.getTags().contains(Tags.GIT_COMMIT_SHA + ":1234"));
  }

  @Test
  @WithConfig(key = "service", value = "Service Name")
  @WithConfig(key = "env", value = "PROD")
  @WithConfig(key = "tags", value = "version:1.0.0-SNAPSHOT")
  void remoteConfigRequestExtraServices() {
    String extraService = "fakeExtraService";
    ServiceNameCollector extraServicesProvider = ServiceNameCollector.get();
    extraServicesProvider.clear();
    extraServicesProvider.addService(extraService);
    PollerRequestFactory factory =
        new PollerRequestFactory(
            Config.get(), TRACER_VERSION, CONTAINER_ID, ENTITY_ID, INVALID_REMOTE_CONFIG_URL, null);

    RemoteConfigRequest request =
        factory.buildRemoteConfigRequest(
            Collections.singletonList("ASM"), null, null, 0, extraServicesProvider);

    assertTrue(request.getClient().getTracerInfo().getExtraServices().contains(extraService));
  }

  @ParameterizedTest(name = "remote config provides process tags when enabled = {0}")
  @ValueSource(booleans = {true, false})
  void remoteConfigProvidesProcessTagsWhenEnabled(boolean enabled) throws Exception {
    if (!enabled) {
      injectSysConfig(EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED, "false");
    }
    ProcessTags.reset(Config.get());
    PollerRequestFactory factory =
        new PollerRequestFactory(
            Config.get(), TRACER_VERSION, CONTAINER_ID, ENTITY_ID, INVALID_REMOTE_CONFIG_URL, null);

    RemoteConfigRequest request =
        factory.buildRemoteConfigRequest(
            Collections.singletonList("ASM"), null, null, 0, ServiceNameCollector.get());
    String json = new Moshi.Builder().build().adapter(RemoteConfigRequest.class).toJson(request);

    List<String> processTags = request.getClient().getTracerInfo().getProcessTags();
    String entrypointName = findMatching(processTags, "entrypoint.name:.+");
    String workingDir = findMatching(processTags, "entrypoint.workdir:.+");

    if (enabled) {
      assertNotNull(workingDir);
      assertNotNull(entrypointName);
      assertThatJson(json).node("client.client_tracer.process_tags").isArray().isNotEmpty();
    } else {
      assertNull(workingDir);
      assertNull(entrypointName);
      assertThatJson(json).node("client.client_tracer.process_tags").isAbsent();
    }
  }

  private static String findMatching(List<String> tags, String regex) {
    if (tags == null) {
      return null;
    }
    Pattern pattern = Pattern.compile(regex);
    return tags.stream().filter(tag -> pattern.matcher(tag).find()).findFirst().orElse(null);
  }
}
