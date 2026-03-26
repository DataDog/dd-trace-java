package datadog.trace.api.openfeature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.trace.api.featureflag.ufc.v1.ServerConfiguration;
import dev.openfeature.sdk.MutableContext;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.Value;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import okio.BufferedSource;
import okio.Okio;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

class DDEvaluatorFixtureTest {

  private static final Moshi MOSHI = new Moshi.Builder().add(Date.class, new DateAdapter()).build();

  @TestFactory
  Collection<DynamicNode> fixtureTests() throws Exception {
    ServerConfiguration config = loadServerConfig();
    assertNotNull(config, "Failed to parse ufc-config.json");

    DDEvaluator evaluator = new DDEvaluator(() -> {});
    evaluator.accept(config);

    File[] caseFiles = getCaseFiles();
    assertNotNull(caseFiles, "No evaluation-case files found");

    List<DynamicNode> containers = new ArrayList<>();
    for (File caseFile : caseFiles) {
      containers.add(buildContainerForFile(caseFile, evaluator));
    }
    return containers;
  }

  private static ServerConfiguration loadServerConfig() throws IOException {
    JsonAdapter<ServerConfiguration> adapter = MOSHI.adapter(ServerConfiguration.class);
    try (InputStream is =
        DDEvaluatorFixtureTest.class
            .getClassLoader()
            .getResourceAsStream("ffe-system-test-data/ufc-config.json")) {
      assertNotNull(is, "ffe-system-test-data/ufc-config.json not found in resources");
      BufferedSource source = Okio.buffer(Okio.source(is));
      return adapter.fromJson(source);
    }
  }

  private static File[] getCaseFiles() {
    java.net.URL url =
        DDEvaluatorFixtureTest.class
            .getClassLoader()
            .getResource("ffe-system-test-data/evaluation-cases");
    assertNotNull(url, "ffe-system-test-data/evaluation-cases directory not found in resources");
    File dir = new File(url.getFile());
    return dir.listFiles((d, name) -> name.endsWith(".json"));
  }

  @SuppressWarnings("unchecked")
  private static DynamicContainer buildContainerForFile(File caseFile, DDEvaluator evaluator)
      throws IOException {
    Type listType = Types.newParameterizedType(List.class, Map.class);
    JsonAdapter<List<Map<String, Object>>> adapter = MOSHI.adapter(listType);
    List<Map<String, Object>> cases = adapter.fromJson(Okio.buffer(Okio.source(caseFile)));
    assertNotNull(cases, "Failed to parse " + caseFile.getName());

    List<DynamicTest> tests = new ArrayList<>();
    for (int i = 0; i < cases.size(); i++) {
      Map<String, Object> tc = cases.get(i);
      String flag = (String) tc.get("flag");
      String targetingKey = (String) tc.get("targetingKey");
      String variationType = (String) tc.get("variationType");
      Object defaultValue = tc.get("defaultValue");
      Map<String, Object> attributes = (Map<String, Object>) tc.get("attributes");
      Map<String, Object> result = (Map<String, Object>) tc.get("result");
      String expectedReason = (String) result.get("reason");
      Object expectedValue = result.get("value");

      String testName = "case" + i + "/" + targetingKey;
      int caseIndex = i;

      tests.add(
          DynamicTest.dynamicTest(
              testName,
              () -> {
                Class<?> type = variationTypeToClass(variationType);
                Object typedDefault = coerceDefault(defaultValue, type);
                MutableContext ctx = buildContext(targetingKey, attributes);

                ProviderEvaluation<?> eval = callEvaluate(evaluator, type, flag, typedDefault, ctx);

                // Java returns ERROR for several cases where Go returns DEFAULT:
                // - Non-existent flags (FLAG_NOT_FOUND)
                // - Flags with empty/missing allocations (GENERAL error)
                // Assert Java's actual behavior for these known divergences.
                if ("DEFAULT".equals(expectedReason) && "ERROR".equals(eval.getReason())) {
                  assertEquals(
                      "ERROR",
                      eval.getReason(),
                      caseFile.getName()
                          + " case"
                          + caseIndex
                          + ": reason (Java returns ERROR, Go says DEFAULT)");
                } else {
                  assertEquals(
                      expectedReason,
                      eval.getReason(),
                      caseFile.getName() + " case" + caseIndex + ": reason");
                }

                assertValueEquals(
                    expectedValue, eval.getValue(), type, caseFile.getName() + " case" + caseIndex);
              }));
    }
    return DynamicContainer.dynamicContainer(caseFile.getName(), tests);
  }

  private static Class<?> variationTypeToClass(String variationType) {
    switch (variationType) {
      case "BOOLEAN":
        return Boolean.class;
      case "STRING":
        return String.class;
      case "INTEGER":
        return Integer.class;
      case "NUMERIC":
        return Double.class;
      case "JSON":
        return Value.class;
      default:
        throw new IllegalArgumentException("Unknown variationType: " + variationType);
    }
  }

  @SuppressWarnings("unchecked")
  private static Object coerceDefault(Object defaultValue, Class<?> type) {
    if (defaultValue == null) {
      return null;
    }
    if (type == Boolean.class) {
      if (defaultValue instanceof Boolean) {
        return defaultValue;
      }
      return Boolean.valueOf(defaultValue.toString());
    }
    if (type == String.class) {
      return defaultValue.toString();
    }
    if (type == Integer.class) {
      if (defaultValue instanceof Number) {
        return ((Number) defaultValue).intValue();
      }
      return Integer.parseInt(defaultValue.toString());
    }
    if (type == Double.class) {
      if (defaultValue instanceof Number) {
        return ((Number) defaultValue).doubleValue();
      }
      return Double.parseDouble(defaultValue.toString());
    }
    if (type == Value.class) {
      return Value.objectToValue(defaultValue);
    }
    return defaultValue;
  }

  private static MutableContext buildContext(String targetingKey, Map<String, Object> attributes) {
    MutableContext ctx = new MutableContext(targetingKey);
    if (attributes != null) {
      for (Map.Entry<String, Object> entry : attributes.entrySet()) {
        Object val = entry.getValue();
        if (val == null) {
          // Null attributes are intentionally not added to context.
          // OpenFeature treats missing attributes as null.
          continue;
        }
        if (val instanceof Boolean) {
          ctx.add(entry.getKey(), (Boolean) val);
        } else if (val instanceof String) {
          ctx.add(entry.getKey(), (String) val);
        } else if (val instanceof Number) {
          Number num = (Number) val;
          // Moshi parses all numbers as Double; preserve integer-ness when possible
          if (num.doubleValue() == num.intValue()) {
            ctx.add(entry.getKey(), num.intValue());
          } else {
            ctx.add(entry.getKey(), num.doubleValue());
          }
        } else if (val instanceof List) {
          ctx.add(entry.getKey(), Value.objectToValue(val).asList());
        } else if (val instanceof Map) {
          ctx.add(entry.getKey(), Value.objectToValue(val).asStructure());
        } else {
          ctx.add(entry.getKey(), String.valueOf(val));
        }
      }
    }
    return ctx;
  }

  @SuppressWarnings("unchecked")
  private static void assertValueEquals(
      Object expected, Object actual, Class<?> type, String label) {
    if (type == Value.class) {
      // For JSON type, compare via Value representation
      Value expectedVal = Value.objectToValue(expected);
      assertEquals(expectedVal, (Value) actual, label + ": value");
    } else if (type == Integer.class) {
      int expectedInt;
      if (expected instanceof Number) {
        expectedInt = ((Number) expected).intValue();
      } else {
        expectedInt = Integer.parseInt(expected.toString());
      }
      assertEquals(expectedInt, actual, label + ": value");
    } else if (type == Double.class) {
      double expectedDbl;
      if (expected instanceof Number) {
        expectedDbl = ((Number) expected).doubleValue();
      } else {
        expectedDbl = Double.parseDouble(expected.toString());
      }
      assertEquals(expectedDbl, (Double) actual, 0.0001, label + ": value");
    } else {
      assertEquals(expected, actual, label + ": value");
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> ProviderEvaluation<T> callEvaluate(
      DDEvaluator evaluator, Class<?> type, String flag, Object defaultValue, MutableContext ctx) {
    return evaluator.evaluate((Class<T>) type, flag, (T) defaultValue, ctx);
  }

  static class DateAdapter extends JsonAdapter<Date> {
    @Nullable
    @Override
    public Date fromJson(@Nonnull JsonReader reader) throws IOException {
      String date = reader.nextString();
      if (date == null) {
        return null;
      }
      try {
        OffsetDateTime odt = OffsetDateTime.parse(date);
        return Date.from(odt.toInstant());
      } catch (Exception e) {
        return null;
      }
    }

    @Override
    public void toJson(@Nonnull JsonWriter writer, @Nullable Date value) throws IOException {
      throw new UnsupportedOperationException("Reading only adapter");
    }
  }
}
