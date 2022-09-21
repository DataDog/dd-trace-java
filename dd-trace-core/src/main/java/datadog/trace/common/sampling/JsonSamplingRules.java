package datadog.trace.common.sampling;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.util.List;

/** Represents list of sampling rules read from JSON. See TRACE_SAMPLING_RULES */
public class JsonSamplingRules {

  public static class Rule {
    String service;
    String name;
    Double sample_rate;
  }

  public static JsonSamplingRules deserialize(String json) throws IOException {
    return new JsonSamplingRules(LIST_OF_RULES_ADAPTER.fromJson(json));
  }

  private static final Moshi MOSHI = new Moshi.Builder().build();
  private static final ParameterizedType LIST_OF_RULES =
      Types.newParameterizedType(List.class, Rule.class);
  private static final JsonAdapter<List<Rule>> LIST_OF_RULES_ADAPTER = MOSHI.adapter(LIST_OF_RULES);

  private final List<Rule> rules;

  public JsonSamplingRules(List<Rule> rules) {
    this.rules = rules;
  }

  public List<Rule> getRules() {
    return rules;
  }

  public boolean isEmpty() {
    return rules.isEmpty();
  }
}
