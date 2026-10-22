package datadog.smoketest.openfeature.legacy;

import datadog.trace.api.openfeature.Provider;
import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.ImmutableStructure;
import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.Value;
import java.util.Map;

/** Registers a released provider and reports how it evaluates flags. */
public final class LegacyProviderApplication {
  private LegacyProviderApplication() {}

  public static void main(final String[] args) {
    final OpenFeatureAPI api = OpenFeatureAPI.getInstance();
    try {
      final Provider provider = new Provider();
      api.setProviderAndWait(provider);
      final Client client = api.getClient();
      System.out.println("PROVIDER " + client.getProviderState());
      System.out.println("PROVIDER_HOOKS " + provider.getProviderHooks().size());
      final Value object = new Value(new ImmutableStructure(Map.of("fallback", new Value(true))));
      report("boolean", client.getBooleanDetails("boolean", true), true);
      report("string", client.getStringDetails("string", "fallback"), "fallback");
      report("integer", client.getIntegerDetails("integer", -1), -1);
      report("double", client.getDoubleDetails("double", -1.0), -1.0);
      report("object", client.getObjectDetails("object", object), object);
    } finally {
      api.shutdown();
    }
  }

  private static void report(
      final String type, final FlagEvaluationDetails<?> details, final Object defaultValue) {
    System.out.println(
        "EVALUATION "
            + type
            + " default="
            + defaultValue.equals(details.getValue())
            + " reason="
            + details.getReason()
            + " error="
            + details.getErrorCode()
            + " variant="
            + details.getVariant());
  }
}
