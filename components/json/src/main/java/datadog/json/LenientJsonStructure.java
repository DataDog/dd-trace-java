package datadog.json;

/** A permissive {@link JsonStructure} that performs no structural checks on the built JSON. */
class LenientJsonStructure implements JsonStructure {
  LenientJsonStructure() {}

  @Override
  public void beginObject() {}

  @Override
  public boolean objectStarted() {
    return true;
  }

  @Override
  public void endObject() {}

  @Override
  public void beginArray() {}

  @Override
  public boolean arrayStarted() {
    return true;
  }

  @Override
  public void endArray() {}

  @Override
  public void addName() {}

  @Override
  public void addValue() {}
}
