package datadog.trace.util.stacktrace;

import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class StackTraceEvent {

  public static final String DEFAULT_LANGUAGE = "java";

  @Nullable private final String language;
  @Nullable private final String id;
  @Nullable private final String message;
  @Nonnull private final List<StackTraceFrame> frames;

  public StackTraceEvent(
      @Nonnull final List<StackTraceFrame> frames,
      @Nullable final String language,
      @Nullable final String id,
      @Nullable final String message) {
    this.language = language;
    this.id = id;
    this.message = message;
    this.frames = frames;
  }

  @Nullable
  public String getLanguage() {
    return language;
  }

  @Nullable
  public String getId() {
    return id;
  }

  @Nullable
  public String getMessage() {
    return message;
  }

  @Nonnull
  public List<StackTraceFrame> getFrames() {
    return frames;
  }
}
