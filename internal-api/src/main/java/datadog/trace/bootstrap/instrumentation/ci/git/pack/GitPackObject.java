package datadog.trace.bootstrap.instrumentation.ci.git.pack;

public class GitPackObject {

  public static final int NOT_FOUND_SHA_INDEX = -1;
  public static final GitPackObject ERROR_PACK_OBJECT = new GitPackObject(0, (byte) 0, null, true);
  public static final GitPackObject NOT_FOUND_PACK_OBJECT =
      new GitPackObject(NOT_FOUND_SHA_INDEX, (byte) 0, null, false);

  private final int shaIndex;
  private final byte type;
  private final byte[] deflatedContent;
  private final boolean error;

  public GitPackObject(
      final int shaIndex, final byte type, final byte[] compressedContent, final boolean error) {
    this.shaIndex = shaIndex;
    this.type = type;
    this.deflatedContent = compressedContent;
    this.error = error;
  }

  public int getShaIndex() {
    return shaIndex;
  }

  public byte getType() {
    return type;
  }

  public byte[] getDeflatedContent() {
    return deflatedContent;
  }

  public boolean raisedError() {
    return error;
  }
}
