package datadog.trace.api;

import datadog.trace.api.TagMap.Entry;

/** The {@link IdStore} view of a {@link HashStore}'s known tags; see {@link HashStore#idStore}. */
final class HashIdStore extends IdStore {
  private final HashStore hash;

  HashIdStore(HashStore hash) {
    this.hash = hash;
  }

  @Override
  public Entry get(long tagId) {
    return this.hash.getById(tagId);
  }

  @Override
  public Object getKnownValue(long tagId) {
    return this.hash.getKnownValue(tagId);
  }

  @Override
  public void set(long tagId, Object value) {
    this.hash.setKnown(tagId, value);
  }

  @Override
  public void set(long tagId, CharSequence value) {
    this.hash.setKnown(tagId, value);
  }

  @Override
  public void set(long tagId, boolean value) {
    this.hash.setKnown(tagId, value);
  }

  @Override
  public void set(long tagId, int value) {
    this.hash.setKnown(tagId, value);
  }

  @Override
  public void set(long tagId, long value) {
    this.hash.setKnown(tagId, value);
  }

  @Override
  public void set(long tagId, float value) {
    this.hash.setKnown(tagId, value);
  }

  @Override
  public void set(long tagId, double value) {
    this.hash.setKnown(tagId, value);
  }

  @Override
  public Entry put(Entry knownEntry) {
    return this.hash.put(knownEntry);
  }

  @Override
  public Entry getAndPut(Entry knownEntry) {
    return this.hash.getAndPut(knownEntry);
  }

  @Override
  public Entry remove(long tagId) {
    return this.hash.removeById(tagId);
  }
}
