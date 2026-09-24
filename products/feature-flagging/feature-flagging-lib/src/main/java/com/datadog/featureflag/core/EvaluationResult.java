package com.datadog.featureflag.core;

/** A resolved value plus product metadata. Transport and SDK adapters consume this result. */
public final class EvaluationResult<T> {
  public enum ErrorCode {
    PROVIDER_NOT_READY,
    INVALID_CONTEXT,
    FLAG_NOT_FOUND,
    TARGETING_KEY_MISSING,
    TYPE_MISMATCH,
    PARSE_ERROR,
    GENERAL
  }

  public enum Reason {
    DISABLED,
    DEFAULT,
    ERROR,
    TARGETING_MATCH,
    SPLIT,
    STATIC
  }

  private final T value;
  private final String reason;
  private final String variant;
  private final ErrorCode errorCode;
  private final String errorMessage;
  private final EvaluationMetadata metadata;
  private final boolean logExposure;

  private EvaluationResult(final Builder<T> builder) {
    value = builder.value;
    reason = builder.reason;
    variant = builder.variant;
    errorCode = builder.errorCode;
    errorMessage = builder.errorMessage;
    metadata = builder.metadata;
    logExposure = builder.logExposure;
  }

  public T getValue() {
    return value;
  }

  public String getReason() {
    return reason;
  }

  public String getVariant() {
    return variant;
  }

  public ErrorCode getErrorCode() {
    return errorCode;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public EvaluationMetadata getFlagMetadata() {
    return metadata;
  }

  public boolean isLogExposure() {
    return logExposure;
  }

  public static <T> Builder<T> builder() {
    return new Builder<>();
  }

  public static final class Builder<T> {
    private T value;
    private String reason;
    private String variant;
    private ErrorCode errorCode;
    private String errorMessage;
    private EvaluationMetadata metadata;
    private boolean logExposure;

    public Builder<T> value(T value) {
      this.value = value;
      return this;
    }

    public Builder<T> reason(String reason) {
      this.reason = reason;
      return this;
    }

    public Builder<T> variant(String variant) {
      this.variant = variant;
      return this;
    }

    public Builder<T> errorCode(ErrorCode errorCode) {
      this.errorCode = errorCode;
      return this;
    }

    public Builder<T> errorMessage(String errorMessage) {
      this.errorMessage = errorMessage;
      return this;
    }

    public Builder<T> flagMetadata(EvaluationMetadata metadata) {
      this.metadata = metadata;
      return this;
    }

    public Builder<T> logExposure(boolean logExposure) {
      this.logExposure = logExposure;
      return this;
    }

    public EvaluationResult<T> build() {
      return new EvaluationResult<>(this);
    }
  }
}
