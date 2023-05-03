package datadog.trace.plugin.csi;

import datadog.trace.plugin.csi.util.ErrorCode;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;

public interface HasErrors {

  List<Failure> getErrors();

  void addError(@Nonnull Failure failure);

  default void addError(@Nonnull final ErrorCode error, final Object... args) {
    addError(new Failure(error, args));
  }

  default void addError(
      @Nonnull final Throwable cause, @Nonnull final ErrorCode error, final Object... args) {
    addError(new Failure(cause, error, args));
  }

  default boolean isSuccess() {
    return !getErrors().isEmpty();
  }

  final class Failure {
    private final ErrorCode error;
    private final Object[] params;
    private final Throwable cause;

    public Failure(@Nonnull final ErrorCode error, @Nonnull final Object... params) {
      this.error = error;
      this.params = params;
      this.cause = null;
    }

    public Failure(
        @Nonnull final Throwable cause,
        @Nonnull final ErrorCode error,
        @Nonnull final Object... params) {
      this.error = error;
      this.params = params;
      this.cause = cause;
    }

    public ErrorCode getErrorCode() {
      return error;
    }

    public Object[] getParams() {
      return params;
    }

    public Throwable getCause() {
      return cause;
    }

    public String getCauseString() {
      if (cause == null) {
        return null;
      }
      StringWriter writer = new StringWriter();
      cause.printStackTrace(new PrintWriter(writer));
      return writer.toString();
    }

    public String getMessage() {
      return error.apply(params);
    }

    @Override
    public String toString() {
      return error.name();
    }
  }

  class HasErrorsImpl implements HasErrors {

    private final List<Failure> errors;

    public HasErrorsImpl(@Nonnull final Collection<Failure> errors) {
      this.errors = new ArrayList<>(errors);
    }

    public HasErrorsImpl(@Nonnull final Failure... errors) {
      this(Arrays.asList(errors));
    }

    public List<Failure> getErrors() {
      return errors;
    }

    @Override
    public boolean isSuccess() {
      return errors.isEmpty();
    }

    @Override
    public void addError(@Nonnull final Failure failure) {
      errors.add(failure);
    }
  }

  class HasErrorsException extends RuntimeException implements HasErrors {
    private final HasErrors errors;

    public HasErrorsException(@Nonnull final HasErrors errors) {
      super(buildMessage(errors), firstCause(errors));
      this.errors = errors;
    }

    public HasErrorsException(@Nonnull final Collection<Failure> errors) {
      this(new HasErrorsImpl(errors));
    }

    public HasErrorsException(@Nonnull final Failure... errors) {
      this(new HasErrorsImpl(errors));
    }

    @Override
    public List<Failure> getErrors() {
      return errors.getErrors();
    }

    @Override
    public void addError(@Nonnull final Failure failure) {
      errors.addError(failure);
    }

    private static String buildMessage(@Nonnull final HasErrors errors) {
      return errors.getErrors().stream()
          .map(Failure::getMessage)
          .collect(Collectors.joining(" | "));
    }

    private static Throwable firstCause(@Nonnull final HasErrors errors) {
      return errors.getErrors().stream()
          .map(Failure::getCause)
          .filter(Objects::nonNull)
          .findFirst()
          .orElse(null);
    }
  }
}
