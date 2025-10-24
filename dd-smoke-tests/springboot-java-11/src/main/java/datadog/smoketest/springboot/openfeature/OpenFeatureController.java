package datadog.smoketest.springboot.openfeature;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.FeatureProvider;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.MutableContext;
import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.Value;
import java.util.HashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/openfeature")
public class OpenFeatureController {

  private final Client client;

  static {
    OpenFeatureAPI.getInstance().setProvider(new datadog.trace.api.openfeature.Provider());
  }

  public OpenFeatureController() {
    this.client = OpenFeatureAPI.getInstance().getClient();
  }

  @GetMapping("/boolean")
  public Map<String, Object> evaluateBooleanFlag(
      @RequestHeader(name = "X-User-Id") String userId,
      @RequestParam(defaultValue = "test-boolean-flag") String flagKey,
      @RequestParam(defaultValue = "false") boolean defaultValue) {
    FlagEvaluationDetails<Boolean> evaluation =
        client.getBooleanDetails(flagKey, defaultValue, createContext(userId));
    return createDetailedResponse(evaluation);
  }

  @GetMapping("/string")
  public Map<String, Object> evaluateStringFlag(
      @RequestHeader(name = "X-User-Id") String userId,
      @RequestParam String flagKey,
      @RequestParam String defaultValue) {
    FlagEvaluationDetails<String> evaluation =
        client.getStringDetails(flagKey, defaultValue, createContext(userId));
    return createDetailedResponse(evaluation);
  }

  @GetMapping("/integer")
  public Map<String, Object> evaluateIntegerFlag(
      @RequestHeader(name = "X-User-Id") String userId,
      @RequestParam String flagKey,
      @RequestParam int defaultValue) {
    FlagEvaluationDetails<Integer> evaluation =
        client.getIntegerDetails(flagKey, defaultValue, createContext(userId));
    return createDetailedResponse(evaluation);
  }

  @GetMapping("/double")
  public Map<String, Object> evaluateDoubleFlag(
      @RequestHeader(name = "X-User-Id") String userId,
      @RequestParam String flagKey,
      @RequestParam double defaultValue) {
    FlagEvaluationDetails<Double> evaluation =
        client.getDoubleDetails(flagKey, defaultValue, createContext(userId));
    return createDetailedResponse(evaluation);
  }

  @GetMapping("/object")
  public Map<String, Object> evaluateObjectFlag(
      @RequestHeader(name = "X-User-Id") String userId, @RequestParam String flagKey) {

    Map<String, Object> defaultMap = new HashMap<>();
    defaultMap.put("default", true);
    Value defaultValue = Value.objectToValue(defaultMap);
    FlagEvaluationDetails<Value> evaluation =
        client.getObjectDetails(flagKey, defaultValue, createContext(userId));
    return createDetailedResponse(evaluation);
  }

  @GetMapping("/provider-metadata")
  public Map<String, Object> getProviderMetadata() {
    FeatureProvider provider = OpenFeatureAPI.getInstance().getProvider();

    Map<String, Object> response = new HashMap<>();
    response.put("providerClass", provider.getClass());
    response.put("metadata", provider.getMetadata().getName());
    return response;
  }

  private <T> Map<String, Object> createDetailedResponse(FlagEvaluationDetails<T> evaluation) {
    Map<String, Object> response = new HashMap<>();
    response.put("flagKey", evaluation.getFlagKey());
    response.put("value", evaluation.getValue());
    response.put("variant", evaluation.getVariant());
    response.put("reason", evaluation.getReason());
    response.put("errorCode", evaluation.getErrorCode());
    response.put("errorMessage", evaluation.getErrorMessage());
    response.put("flagMetadata", evaluation.getFlagMetadata());
    return response;
  }

  private EvaluationContext createContext(String userId) {
    MutableContext context = new MutableContext();
    context.setTargetingKey("user");
    context.add("user", userId);
    context.add("environment", "test");
    context.add("application", "smoketest");
    return context;
  }
}
