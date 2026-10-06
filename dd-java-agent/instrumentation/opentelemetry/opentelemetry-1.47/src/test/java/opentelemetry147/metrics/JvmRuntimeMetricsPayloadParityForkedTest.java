package opentelemetry147.metrics;

import static datadog.metrics.impl.statsd.DDAgentStatsDClientManager.statsDClientManager;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.datadog.jmxfetch.AppConfig.ACTION_COLLECT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.json.JsonMapper;
import datadog.metrics.api.statsd.StatsDClient;
import datadog.trace.agent.jmxfetch.AgentConnectionFactory;
import datadog.trace.agent.jmxfetch.AgentStatsdReporter;
import datadog.trace.agent.jmxfetch.JvmOtlpRuntimeMetrics;
import datadog.trace.api.Config;
import datadog.trace.api.time.SystemTimeSource;
import datadog.trace.core.otlp.common.OtlpPayload;
import datadog.trace.core.otlp.metrics.OtlpMetricsJsonCollector;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.datadog.jmxfetch.App;
import org.datadog.jmxfetch.AppConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

// Forked because Config, ProcessTags, the StatsD manager, and JvmOtlpRuntimeMetrics all cache
// process-wide state. This test compares the actual wire payloads produced by both pipelines.
class JvmRuntimeMetricsPayloadParityForkedTest {

  private static final Set<String> COMMON_TAGS =
      new HashSet<>(
          Arrays.asList(
              "instance",
              "dd.internal.jmx_check_name",
              "runtime-id",
              "entrypoint.name",
              "entrypoint.type",
              "entrypoint.workdir",
              "_dd.injection.mode",
              "jmx_domain",
              "type",
              "name"));

  private static List<MetricPoint> jmxFetchPoints;
  private static List<MetricPoint> otlpPoints;

  @BeforeAll
  static void collectPayloads() throws IOException {
    System.setProperty("dd.dogstatsd.start-delay", "0");
    System.setProperty("dd.tags", "_dd.injection.mode:test-injection");
    System.setProperty("dd.metrics.otel.enabled", "true");

    JvmOtlpRuntimeMetrics.start(true);
    jmxFetchPoints = parseDogStatsdPayload(collectJmxFetchPayload());
    otlpPoints = parseOtlpPayload(collectOtlpPayload());
  }

  @Test
  void actualPayloadsHaveMatchingMigrationTagsForRuntimeBeans() {
    Map<BeanId, List<MetricPoint>> jmxByBean = pointsByBean(jmxFetchPoints);
    Map<BeanId, List<MetricPoint>> otlpByBean = pointsByBean(otlpPoints);

    Set<BeanId> sharedBeans = new HashSet<>(jmxByBean.keySet());
    sharedBeans.retainAll(otlpByBean.keySet());

    assertContainsBeanType(sharedBeans, "Memory");
    assertContainsBeanType(sharedBeans, "Threading");
    assertContainsBeanType(sharedBeans, "ClassLoading");
    assertContainsBeanType(sharedBeans, "OperatingSystem");
    assertContainsBeanType(sharedBeans, "BufferPool");

    for (BeanId bean : sharedBeans) {
      Map<String, String> expected = commonTags(jmxByBean.get(bean).get(0).tags);
      assertRequiredTags(expected, "JMXFetch", bean);
      for (MetricPoint point : jmxByBean.get(bean)) {
        assertEquals(
            expected,
            commonTags(point.tags),
            "JMXFetch migration tags differ within " + bean + " on metric " + point.name);
      }
      for (MetricPoint point : otlpByBean.get(bean)) {
        Map<String, String> actual = commonTags(point.tags);
        assertEquals(
            expected,
            actual,
            "Migration tags differ for " + bean + " on OTLP metric " + point.name);
      }
    }
  }

  private static byte[] collectJmxFetchPayload() throws IOException {
    InetAddress loopback = InetAddress.getByName("127.0.0.1");
    try (DatagramSocket server = new DatagramSocket(0, loopback)) {
      StatsDClient client =
          statsDClientManager()
              .statsDClient(
                  loopback.getHostAddress(), server.getLocalPort(), null, null, null, false);
      try {
        AppConfig appConfig =
            AppConfig.builder()
                .action(Collections.singletonList(ACTION_COLLECT))
                .daemon(true)
                .embedded(true)
                .targetDirectInstances(true)
                .instanceConfigResources(Collections.singletonList("jmxfetch-config.yaml"))
                .globalTags(Config.get().getMergedJmxTags())
                .reporter(new AgentStatsdReporter(client))
                .connectionFactory(new AgentConnectionFactory())
                .build();
        App app = new App(appConfig);
        app.init(false);
        app.doIteration();
        return receiveDatagrams(server);
      } finally {
        client.close();
      }
    }
  }

  private static byte[] receiveDatagrams(DatagramSocket server) throws IOException {
    server.setSoTimeout(5000);
    ByteArrayOutputStream payload = new ByteArrayOutputStream();
    byte[] buffer = new byte[65535];
    while (true) {
      DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
      try {
        server.receive(packet);
      } catch (SocketTimeoutException ignored) {
        break;
      }
      payload.write(packet.getData(), packet.getOffset(), packet.getLength());
      payload.write('\n');
      server.setSoTimeout(250);
    }
    assertTrue(payload.size() > 0, "JMXFetch did not emit a DogStatsD payload");
    return payload.toByteArray();
  }

  private static byte[] collectOtlpPayload() {
    OtlpPayload payload = new OtlpMetricsJsonCollector(SystemTimeSource.INSTANCE).collectMetrics();
    assertTrue(payload.getContentLength() > 0, "OTLP collector produced an empty payload");
    ByteBuffer content = payload.getContent();
    byte[] bytes = new byte[content.remaining()];
    content.get(bytes);
    return bytes;
  }

  private static List<MetricPoint> parseDogStatsdPayload(byte[] payload) {
    List<MetricPoint> points = new ArrayList<>();
    for (String line : new String(payload, UTF_8).split("\\n")) {
      int valueSeparator = line.indexOf(':');
      int tagsSeparator = line.indexOf("|#");
      if (valueSeparator <= 0 || tagsSeparator < 0) {
        continue;
      }
      points.add(
          new MetricPoint(
              line.substring(0, valueSeparator),
              parseColonTags(line.substring(tagsSeparator + 2))));
    }
    assertFalse(points.isEmpty(), "DogStatsD payload did not contain metric points");
    return points;
  }

  @SuppressWarnings("unchecked")
  private static List<MetricPoint> parseOtlpPayload(byte[] payload) throws IOException {
    Map<String, Object> root = JsonMapper.fromJsonToMap(new String(payload, UTF_8));
    List<MetricPoint> points = new ArrayList<>();
    for (Object resourceMetricObject : (List<Object>) root.get("resourceMetrics")) {
      Map<String, Object> resourceMetric = (Map<String, Object>) resourceMetricObject;
      for (Object scopeMetricObject : (List<Object>) resourceMetric.get("scopeMetrics")) {
        Map<String, Object> scopeMetric = (Map<String, Object>) scopeMetricObject;
        Map<String, Object> scope = (Map<String, Object>) scopeMetric.get("scope");
        if (!"datadog.jvm.runtime".equals(scope.get("name"))) {
          continue;
        }
        for (Object metricObject : (List<Object>) scopeMetric.get("metrics")) {
          Map<String, Object> metric = (Map<String, Object>) metricObject;
          for (String dataType : Arrays.asList("gauge", "sum", "histogram")) {
            Map<String, Object> data = (Map<String, Object>) metric.get(dataType);
            if (data == null) {
              continue;
            }
            for (Object pointObject : (List<Object>) data.get("dataPoints")) {
              Map<String, Object> point = (Map<String, Object>) pointObject;
              points.add(
                  new MetricPoint(
                      (String) metric.get("name"), parseOtlpAttributes(point.get("attributes"))));
            }
          }
        }
      }
    }
    assertFalse(points.isEmpty(), "OTLP payload did not contain JVM runtime metric points");
    return points;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, String> parseOtlpAttributes(Object attributesObject) {
    Map<String, String> attributes = new LinkedHashMap<>();
    if (attributesObject == null) {
      return attributes;
    }
    for (Object attributeObject : (List<Object>) attributesObject) {
      Map<String, Object> attribute = (Map<String, Object>) attributeObject;
      Map<String, Object> value = (Map<String, Object>) attribute.get("value");
      Object stringValue = value.get("stringValue");
      if (stringValue != null) {
        attributes.put((String) attribute.get("key"), stringValue.toString());
      }
    }
    return attributes;
  }

  private static Map<String, String> parseColonTags(String serializedTags) {
    Map<String, String> tags = new LinkedHashMap<>();
    for (String tag : serializedTags.split(",")) {
      int separator = tag.indexOf(':');
      if (separator > 0) {
        tags.put(tag.substring(0, separator), tag.substring(separator + 1));
      }
    }
    return tags;
  }

  private static Map<BeanId, List<MetricPoint>> pointsByBean(List<MetricPoint> points) {
    Map<BeanId, List<MetricPoint>> byBean = new HashMap<>();
    for (MetricPoint point : points) {
      BeanId bean = BeanId.from(point.tags);
      if (bean != null) {
        byBean.computeIfAbsent(bean, ignored -> new ArrayList<>()).add(point);
      }
    }
    return byBean;
  }

  private static Map<String, String> commonTags(Map<String, String> tags) {
    Map<String, String> common = new LinkedHashMap<>();
    for (String key : COMMON_TAGS) {
      if (tags.containsKey(key)) {
        common.put(key, tags.get(key));
      }
    }
    return common;
  }

  private static void assertRequiredTags(
      Map<String, String> tags, String payloadName, BeanId bean) {
    for (String key :
        Arrays.asList(
            "instance",
            "dd.internal.jmx_check_name",
            "runtime-id",
            "entrypoint.name",
            "entrypoint.type",
            "entrypoint.workdir",
            "_dd.injection.mode",
            "jmx_domain",
            "type")) {
      assertTrue(tags.containsKey(key), payloadName + " is missing tag " + key + " for " + bean);
    }
  }

  private static void assertContainsBeanType(Set<BeanId> beans, String type) {
    assertTrue(
        beans.stream().anyMatch(bean -> type.equals(bean.type)),
        "No shared runtime bean with type " + type + "; shared beans: " + beans);
  }

  private static final class MetricPoint {
    private final String name;
    private final Map<String, String> tags;

    private MetricPoint(String name, Map<String, String> tags) {
      this.name = name;
      this.tags = tags;
    }
  }

  private static final class BeanId {
    private final String domain;
    private final String type;
    private final String name;

    private BeanId(String domain, String type, String name) {
      this.domain = domain;
      this.type = type;
      this.name = name;
    }

    private static BeanId from(Map<String, String> tags) {
      String domain = tags.get("jmx_domain");
      String type = tags.get("type");
      return domain == null || type == null ? null : new BeanId(domain, type, tags.get("name"));
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (!(other instanceof BeanId)) {
        return false;
      }
      BeanId that = (BeanId) other;
      return domain.equals(that.domain)
          && type.equals(that.type)
          && Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
      return Objects.hash(domain, type, name);
    }

    @Override
    public String toString() {
      return domain + ":type=" + type + (name == null ? "" : ",name=" + name);
    }
  }
}
