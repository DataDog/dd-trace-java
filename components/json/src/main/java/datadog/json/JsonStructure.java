package datadog.json;

/** The {@link JsonStructure} keeps track of JSON value being built. */
interface JsonStructure {
  /**
   * Begins an object.
   *
   * @throws IllegalStateException if the object can not be started at this position.
   */
  void beginObject();

  /**
   * Checks whether the current position is within an object.
   *
   * @return {@code true} if the current position is within an object, {@code false} otherwise.
   */
  boolean objectStarted();

  /**
   * Ends the current object.
   *
   * @throws IllegalStateException if the current position is not within an object.
   */
  void endObject();

  /** Begins an array. */
  void beginArray();

  /**
   * Checks whether the current position is within an array.
   *
   * @return {@code true} if the current position is within an array, {@code false} otherwise.
   */
  boolean arrayStarted();

  /**
   * Ends the current array.
   *
   * @throws IllegalStateException if the current position is not within an array.
   */
  void endArray();

  /**
   * Adds a name to the current object.
   *
   * @throws IllegalStateException if the current position is not within an object.
   */
  void addName();

  /**
   * Adds a value.
   *
   * @throws IllegalStateException if the current position can not have a value.
   */
  void addValue();
}
