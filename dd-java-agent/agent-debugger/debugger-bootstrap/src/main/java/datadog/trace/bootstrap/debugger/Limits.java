package datadog.trace.bootstrap.debugger;

/** Stores data extraction limits and provide defaults */
public class Limits {
  public static final int DEFAULT_REFERENCE_DEPTH = 3;
  public static final int DEFAULT_COLLECTION_SIZE = 100;
  public static final int DEFAULT_LENGTH = 255;
  public static final int DEFAULT_FIELD_COUNT = 20;

  public final int maxReferenceDepth;
  public final int maxCollectionSize;
  public final int maxLength;
  public final int maxFieldCount;

  public static final Limits DEFAULT =
      new Limits(
          DEFAULT_REFERENCE_DEPTH, DEFAULT_COLLECTION_SIZE, DEFAULT_LENGTH, DEFAULT_FIELD_COUNT);

  public Limits(int maxReferenceDepth, int maxCollectionSize, int maxLength, int maxFieldCount) {
    this.maxReferenceDepth = maxReferenceDepth;
    this.maxCollectionSize = maxCollectionSize;
    this.maxLength = maxLength;
    this.maxFieldCount = maxFieldCount;
  }

  public int getMaxReferenceDepth() {
    return maxReferenceDepth;
  }

  public int getMaxCollectionSize() {
    return maxCollectionSize;
  }

  public int getMaxLength() {
    return maxLength;
  }

  public int getMaxFieldCount() {
    return maxFieldCount;
  }

  public static Limits decDepthLimits(Limits current) {
    return new Limits(
        current.maxReferenceDepth - 1,
        current.maxCollectionSize,
        current.maxLength,
        current.maxFieldCount);
  }
}
