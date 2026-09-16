package datadog.trace.core.otlp.common;

import datadog.json.JsonWriter;

/** Tracks a JSON array that's opened lazily, on its first element, and closed once done. */
public final class LazyJsonArray {
  private boolean open;

  /** Opens the named array if it isn't already open. */
  public void ensureOpen(JsonWriter writer, String name) {
    if (!open) {
      writer.name(name).beginArray();
      open = true;
    }
  }

  /** Closes the array if it's currently open. */
  public void closeIfOpen(JsonWriter writer) {
    if (open) {
      writer.endArray();
      open = false;
    }
  }

  /** Resets the tracked state without touching the writer. */
  public void reset() {
    open = false;
  }
}
