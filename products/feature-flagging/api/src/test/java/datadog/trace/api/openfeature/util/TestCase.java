package datadog.trace.api.openfeature.util;

import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.MutableContext;
import dev.openfeature.sdk.Structure;
import dev.openfeature.sdk.Value;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class TestCase<E> {

  public Class<E> type;
  public String flag;
  public E defaultValue;
  private final MutableContext baseContext = new MutableContext();
  private String targetingKeyOverride;
  private boolean hasTargetingKeyOverride = false;
  public Result<E> result;

  // Custom context wrapper that preserves empty targeting key (OF.7 compliance)
  public final EvaluationContext context = new EvaluationContext() {
    @Override
    public String getTargetingKey() {
      return hasTargetingKeyOverride ? targetingKeyOverride : baseContext.getTargetingKey();
    }

    @Override
    public Value getValue(String key) {
      return baseContext.getValue(key);
    }

    @Override
    public Set<String> keySet() {
      return baseContext.keySet();
    }

    @Override
    public Map<String, Value> asMap() {
      return baseContext.asMap();
    }

    @Override
    public Map<String, Object> asObjectMap() {
      return baseContext.asObjectMap();
    }
  };

  @SuppressWarnings("unchecked")
  public TestCase(final E defaultValue) {
    this.type = (Class<E>) defaultValue.getClass();
    this.defaultValue = defaultValue;
  }

  public TestCase<E> flag(String flag) {
    this.flag = flag;
    return this;
  }

  public TestCase<E> targetingKey(final String targetingKey) {
    // Preserve the targeting key directly to support empty string (OF.7)
    this.targetingKeyOverride = targetingKey;
    this.hasTargetingKeyOverride = true;
    return this;
  }

  public TestCase<E> context(final String key, final String value) {
    baseContext.add(key, value);
    return this;
  }

  public TestCase<E> context(final String key, final Integer value) {
    baseContext.add(key, value);
    return this;
  }

  public TestCase<E> context(final String key, final Double value) {
    baseContext.add(key, value);
    return this;
  }

  public TestCase<E> context(final String key, final Boolean value) {
    baseContext.add(key, value);
    return this;
  }

  public TestCase<E> context(final String key, final Structure value) {
    baseContext.add(key, value);
    return this;
  }

  public TestCase<E> context(final String key, final List<Value> value) {
    baseContext.add(key, value);
    return this;
  }

  public TestCase<E> result(final Result<E> result) {
    this.result = result;
    return this;
  }

  @Override
  public String toString() {
    return "TestCase{"
        + "flag='"
        + flag
        + '\''
        + ", defaultValue="
        + defaultValue
        + ", targetingKey="
        + context.getTargetingKey()
        + '}';
  }

  public static class Result<E> {
    public E value;
    public String variant;
    public String[] reason;
    public ErrorCode errorCode;
    public final Map<String, Object> flagMetadata = new HashMap<>();

    public Result(final E value) {
      this.value = value;
    }

    public Result<E> variant(final String variant) {
      this.variant = variant;
      return this;
    }

    public Result<E> errorCode(final ErrorCode errorCode) {
      this.errorCode = errorCode;
      return this;
    }

    public Result<E> reason(final String... reason) {
      this.reason = reason;
      return this;
    }

    public Result<E> flagMetadata(final String name, final Object value) {
      flagMetadata.put(name, value);
      return this;
    }
  }
}
