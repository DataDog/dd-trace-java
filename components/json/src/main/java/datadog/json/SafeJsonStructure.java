package datadog.json;

import java.util.BitSet;

/**
 * This {@link JsonStructure} performs minimal structure checks to ensure the built JSON is
 * coherent.
 */
class SafeJsonStructure implements JsonStructure {
  private final BitSet structure;
  private int depth;
  private boolean complete;

  SafeJsonStructure() {
    this.structure = new BitSet();
    this.depth = -1;
    this.complete = false;
  }

  @Override
  public void beginObject() {
    if (this.complete) {
      throw new IllegalStateException("Object is complete");
    }
    this.structure.set(++this.depth);
  }

  @Override
  public boolean objectStarted() {
    return this.depth >= 0 && this.structure.get(this.depth);
  }

  @Override
  public void endObject() {
    if (!objectStarted()) {
      throw new IllegalStateException("Object not started");
    }
    this.depth--;
    if (this.depth < 0) {
      this.complete = true;
    }
  }

  @Override
  public void beginArray() {
    if (this.complete) {
      throw new IllegalStateException("Object is complete");
    }
    this.structure.clear(++this.depth);
  }

  @Override
  public boolean arrayStarted() {
    return this.depth >= 0 && !this.structure.get(this.depth);
  }

  @Override
  public void endArray() {
    if (!arrayStarted()) {
      throw new IllegalStateException("Array not started");
    }
    this.depth--;
    if (this.depth < 0) {
      this.complete = true;
    }
  }

  @Override
  public void addName() {
    if (!objectStarted()) {
      throw new IllegalStateException("Object not started");
    }
  }

  @Override
  public void addValue() {
    if (this.complete) {
      throw new IllegalStateException("Object is complete");
    }
    if (this.depth < 0) {
      this.complete = true;
    }
  }

  @Override
  public String toString() {
    return (this.complete ? "complete" : "") + this.structure;
  }
}
