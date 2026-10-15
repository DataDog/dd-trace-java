package com.datadog.profiling.otel.validation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Result of OTLP profile validation containing errors and warnings. */
public final class ValidationResult {
  private final List<String> errors;
  private final List<String> warnings;

  private ValidationResult(List<String> errors, List<String> warnings) {
    this.errors = Collections.unmodifiableList(new ArrayList<>(errors));
    this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
  }

  /**
   * Creates a new builder for validation results.
   *
   * @return a new builder instance
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Returns whether validation passed (no errors).
   *
   * @return true if no errors were found
   */
  public boolean isValid() {
    return errors.isEmpty();
  }

  /**
   * Returns the list of validation errors.
   *
   * @return unmodifiable list of error messages
   */
  public List<String> getErrors() {
    return errors;
  }

  /**
   * Returns the list of validation warnings.
   *
   * @return unmodifiable list of warning messages
   */
  public List<String> getWarnings() {
    return warnings;
  }

  /**
   * Returns a formatted string containing all errors and warnings.
   *
   * @return formatted validation report
   */
  public String getReport() {
    StringBuilder sb = new StringBuilder();
    if (isValid()) {
      sb.append("Validation PASSED");
      if (!warnings.isEmpty()) {
        sb.append(" (").append(warnings.size()).append(" warnings)");
      }
    } else {
      sb.append("Validation FAILED (").append(errors.size()).append(" errors");
      if (!warnings.isEmpty()) {
        sb.append(", ").append(warnings.size()).append(" warnings");
      }
      sb.append(")");
    }

    if (!errors.isEmpty()) {
      sb.append("\n\nErrors:");
      for (String error : errors) {
        sb.append("\n  - ").append(error);
      }
    }

    if (!warnings.isEmpty()) {
      sb.append("\n\nWarnings:");
      for (String warning : warnings) {
        sb.append("\n  - ").append(warning);
      }
    }

    return sb.toString();
  }

  @Override
  public String toString() {
    return getReport();
  }

  /** Builder for creating validation results. */
  public static final class Builder {
    private final List<String> errors = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    private Builder() {}

    /**
     * Adds an error to the validation result.
     *
     * @param message error message
     * @return this builder
     */
    public Builder addError(String message) {
      errors.add(message);
      return this;
    }

    /**
     * Adds a warning to the validation result.
     *
     * @param message warning message
     * @return this builder
     */
    public Builder addWarning(String message) {
      warnings.add(message);
      return this;
    }

    /**
     * Builds the validation result.
     *
     * @return the validation result
     */
    public ValidationResult build() {
      return new ValidationResult(errors, warnings);
    }
  }
}
