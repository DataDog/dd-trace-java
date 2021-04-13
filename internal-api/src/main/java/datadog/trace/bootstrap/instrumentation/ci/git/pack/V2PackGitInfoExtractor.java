package datadog.trace.bootstrap.instrumentation.ci.git.pack;

import static datadog.trace.bootstrap.instrumentation.ci.git.GitObject.COMMIT_TYPE;
import static datadog.trace.bootstrap.instrumentation.ci.git.GitObject.TAG_TYPE;
import static datadog.trace.bootstrap.instrumentation.ci.git.pack.GitPackObject.ERROR_PACK_OBJECT;
import static datadog.trace.bootstrap.instrumentation.ci.git.pack.GitPackObject.NOT_FOUND_PACK_OBJECT;
import static datadog.trace.bootstrap.instrumentation.ci.git.pack.GitPackObject.NOT_FOUND_SHA_INDEX;
import static datadog.trace.bootstrap.instrumentation.ci.git.pack.GitPackUtils.SeekOrigin;
import static datadog.trace.bootstrap.instrumentation.ci.git.pack.GitPackUtils.hexToByteArray;
import static datadog.trace.bootstrap.instrumentation.ci.git.pack.GitPackUtils.readBytes;
import static datadog.trace.bootstrap.instrumentation.ci.git.pack.GitPackUtils.seek;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Arrays;

public class V2PackGitInfoExtractor extends VersionedPackGitInfoExtractor {

  public static final short VERSION = 2;

  private static final int[] INVALID_TYPE_AND_SIZE = new int[] {-1, -1};

  @Override
  public short getVersion() {
    return VERSION;
  }

  @Override
  public GitPackObject extract(final File idxFile, final File packFile, final String commitSha) {
    try {
      final String indexHex = commitSha.substring(0, 2);
      final int index = Integer.parseInt(indexHex, 16);
      final int previousIndex = index - 1;

      final RandomAccessFile idx = new RandomAccessFile(idxFile, "r");

      // Skip header and version
      seek(idx, 8, SeekOrigin.BEGIN);

      int numObjectsPreviousIndex = 0;
      if (previousIndex != -1) {
        // Seek to previousIndex position.
        seek(idx, previousIndex * 4, SeekOrigin.CURRENT);
        numObjectsPreviousIndex = idx.readInt();
      }

      final int numObjectsIndex = idx.readInt() - numObjectsPreviousIndex;
      // Seek to last position. The last position contains the count of all objects.
      seek(idx, (255 - (index + 1)) * 4, SeekOrigin.CURRENT);
      final int totalObjects = idx.readInt();

      // Search the sha index in the second layer: the SHA listing.
      final int shaIndex =
          searchSha(idx, commitSha, totalObjects, numObjectsPreviousIndex, numObjectsIndex);
      if (shaIndex == NOT_FOUND_SHA_INDEX) {
        return NOT_FOUND_PACK_OBJECT;
      }

      // Third layer: 4 byte CRC for each object. We skip it.
      seek(idx, 4 * totalObjects, SeekOrigin.CURRENT);

      // Search packOffset in fourth and fifth layer.
      final long packOffset = searchOffset(idx, shaIndex, totalObjects);

      // Open pack file and seek to packOffset.
      final RandomAccessFile pack = new RandomAccessFile(packFile, "r");
      seek(pack, packOffset, SeekOrigin.BEGIN);

      final int[] gitObjectTypeAndSize = extractGitObjectTypeAndSize(pack);
      if (Arrays.equals(gitObjectTypeAndSize, INVALID_TYPE_AND_SIZE)) {
        return ERROR_PACK_OBJECT;
      }
      return new GitPackObject(
          shaIndex,
          (byte) gitObjectTypeAndSize[TYPE_INDEX],
          readBytes(pack, gitObjectTypeAndSize[SIZE_INDEX]),
          false);
    } catch (final Exception e) {
      return ERROR_PACK_OBJECT;
    }
  }

  protected int searchSha(
      final RandomAccessFile idx,
      final String commitSha,
      final int totalObjects,
      final int numObjectsPreviousIndex,
      final int numObjectsIndex)
      throws IOException {
    // Skip all previous SHAs
    seek(idx, 20 * (numObjectsPreviousIndex), GitPackUtils.SeekOrigin.CURRENT);
    final byte[] shaBytes = hexToByteArray(commitSha);

    // Search target SHA index in the SHA listing table.
    for (int i = 0; i < numObjectsIndex; i++) {
      final byte[] current = readBytes(idx, 20);
      if (Arrays.equals(shaBytes, current)) {
        final int shaIndex = numObjectsPreviousIndex + i;
        seek(idx, -20, GitPackUtils.SeekOrigin.CURRENT);

        // If we find the SHA, we skip all SHA listing table.
        seek(idx, 20 * (totalObjects - shaIndex), SeekOrigin.CURRENT);
        return shaIndex;
      }
    }

    return NOT_FOUND_SHA_INDEX;
  }

  protected long searchOffset(
      final RandomAccessFile idx, final int shaIndex, final int totalObjects) throws IOException {
    // Fourth layer: 4 byte per object of offset in pack file
    seek(idx, 4 * shaIndex, SeekOrigin.CURRENT);
    int offset = idx.readInt();
    seek(idx, -4, SeekOrigin.CURRENT);

    if (((offset >> 31) & 1) == 0) {
      return offset;
    } else {
      // offset is not in this layer, clear first bit and look at it at the 5th layer
      offset &= 0x7FFFFFFF;
      seek(idx, 4 * (totalObjects - shaIndex), SeekOrigin.CURRENT);
      seek(idx, 8 * offset, SeekOrigin.CURRENT);
      return idx.readLong();
    }
  }

  protected int[] extractGitObjectTypeAndSize(final RandomAccessFile pack) throws IOException {
    // We consider that 2 bytes is more than enough to store the commit message.
    final byte[] sizeParts = readBytes(pack, 2);
    if (((sizeParts[1] >> 7) & 1) == 1) {
      return INVALID_TYPE_AND_SIZE;
    }

    final byte type = (byte) ((sizeParts[0] & 0x70) >> 4);
    if (type != COMMIT_TYPE && type != TAG_TYPE) {
      return INVALID_TYPE_AND_SIZE;
    }

    int size = 0;
    size |= (sizeParts[1] & 0x7F);
    size <<= 4;
    size |= (sizeParts[0] & 0x0F);

    return new int[] {type, size};
  }
}
