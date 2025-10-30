package datadog.smoketest.springboot.openfeature;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.FeatureProvider;
import dev.openfeature.sdk.MutableContext;
import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.Structure;
import dev.openfeature.sdk.Value;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/openfeature")
public class OpenFeatureController {

  private static final Logger LOGGER = LoggerFactory.getLogger(OpenFeatureController.class);

  private final Client client;

  static {
    OpenFeatureAPI.getInstance().setProvider(new datadog.trace.api.openfeature.Provider());
  }

  public OpenFeatureController() {
    this.client = OpenFeatureAPI.getInstance().getClient();
  }

  @GetMapping("/provider-metadata")
  public Map<String, Object> getProviderMetadata() {
    FeatureProvider provider = OpenFeatureAPI.getInstance().getProvider();

    Map<String, Object> response = new HashMap<>();
    response.put("providerClass", provider.getClass());
    response.put("metadata", provider.getMetadata().getName());
    return response;
  }

  @PostMapping(
      value = "/evaluate",
      consumes = APPLICATION_JSON_VALUE,
      produces = APPLICATION_JSON_VALUE)
  public ResponseEntity<Map<String, Object>> evaluate(@RequestBody final EvaluateRequest request) {
    Object value;
    String reason;
    final EvaluationContext context = context(request);
    try {
      switch (request.getVariationType()) {
        case "BOOLEAN":
          value =
              client.getBooleanValue(
                  request.getFlag(), (Boolean) request.getDefaultValue(), context);
          break;
        case "STRING":
          value =
              client.getStringValue(request.getFlag(), (String) request.getDefaultValue(), context);
          break;
        case "INTEGER":
          final Number integerEval = (Number) request.getDefaultValue();
          value = client.getIntegerValue(request.getFlag(), integerEval.intValue(), context);
          break;
        case "NUMERIC":
          final Number doubleEval = (Number) request.getDefaultValue();
          value = client.getDoubleValue(request.getFlag(), doubleEval.doubleValue(), context);
          break;
        case "JSON":
          final Value objectValue =
              client.getObjectValue(
                  request.getFlag(), Value.objectToValue(request.getDefaultValue()), context);
          value = context.convertValue(objectValue);
          break;
        default:
          value = request.getDefaultValue();
      }

      reason = "DEFAULT";
    } catch (Throwable e) {
      LOGGER.error("Error on resolution", e);
      value = request.getDefaultValue();
      reason = "ERROR";
    }
    final Map<String, Object> result = new HashMap<>();
    result.put("reason", reason);
    result.put("value", value);
    return ResponseEntity.ok(result);
  }

  private static EvaluationContext context(final EvaluateRequest request) {
    final MutableContext context = new MutableContext();
    context.setTargetingKey(request.getTargetingKey());
    request.attributes.forEach(
        (key, value) -> {
          if (value instanceof Boolean) {
            context.add(key, (Boolean) value);
          } else if (value instanceof Integer) {
            context.add(key, (Integer) value);
          } else if (value instanceof Double) {
            context.add(key, (Double) value);
          } else if (value instanceof String) {
            context.add(key, (String) value);
          } else if (value instanceof Map) {
            context.add(key, Value.objectToValue(value).asStructure());
          } else if (value instanceof List) {
            context.add(key, Value.objectToValue(value).asList());
          } else {
            context.add(key, (Structure) null);
          }
        });
    return context;
  }

  public static class EvaluateRequest {
    private String flag;
    private String variationType;
    private Object defaultValue;
    private String targetingKey;
    private Map<String, Object> attributes;

    public Map<String, Object> getAttributes() {
      return attributes;
    }

    public void setAttributes(Map<String, Object> attributes) {
      this.attributes = attributes;
    }

    public Object getDefaultValue() {
      return defaultValue;
    }

    public void setDefaultValue(Object defaultValue) {
      this.defaultValue = defaultValue;
    }

    public String getFlag() {
      return flag;
    }

    public void setFlag(String flag) {
      this.flag = flag;
    }

    public String getTargetingKey() {
      return targetingKey;
    }

    public void setTargetingKey(String targetingKey) {
      this.targetingKey = targetingKey;
    }

    public String getVariationType() {
      return variationType;
    }

    public void setVariationType(String variationType) {
      this.variationType = variationType;
    }
  }
}
