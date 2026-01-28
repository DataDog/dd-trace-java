package datadog.trace.api.openfeature.util;

import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.MutableContext;
import dev.openfeature.sdk.Structure;
import dev.openfeature.sdk.Value;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TestCase<E> {

  public Class<E> type;
  public String flag;
  public E defaultValue;
  public final MutableContext context = new MutableContext();
  public Result<E> result;

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
    context.setTargetingKey(targetingKey);
    return this;
  }

  public TestCase<E> context(final String key, final String value) {
    context.add(key, value);
    return this;
  }

  public TestCase<E> context(final String key, final Integer value) {
    context.add(key, value);
    return this;
  }

  public TestCase<E> context(final String key, final Double value) {
    context.add(key, value);
    return this;
  }

  public TestCase<E> context(final String key, final Boolean value) {
    context.add(key, value);
    return this;
  }

  public TestCase<E> context(final String key, final Structure value) {
    context.add(key, value);
    return this;
  }

  public TestCase<E> context(final String key, final List<Value> value) {
    context.add(key, value);
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
