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

/**
 * Extract Git information from Git Packfiles v2.
 *
 * <p>Git packfiles is a standard to improve the disk and network usage:
 * https://git-scm.com/docs/pack-format
 *
 * <p>The ".pack" file contains the actual Git object. The ".idx" file contains the index used to
 * quickly locate objects within the ".pack" file.
 *
 * <p>IDX file (v2):
 *
 * <p>header: 4 bytes: [-1, 116, 79, 99] in v2.
 *
 * <p>version: 4 bytes: [0, 0, 0, 2]
 *
 * <p>fanout table: 256 x 4 bytes -> fanout[255] == size
 *
 * <p>sha listing: [size] x 20 bytes
 *
 * <p>crc checksums: [size] x 4 bytes
 *
 * <p>packfile offsets: [size] x 4 bytes
 *
 * <p>large packfile offsets: N x 8 bytes. (N is number of 4 bytes offsets that have the high bit
 * set).This group only appears in indices of packfiles larger than 2Gb.
 *
 * <p>packfile checksum SHA1: 20 bytes
 *
 * <p>idxfile checksum SHA1: 20 bytes.
 *
 * <p>Pack file (v2):
 *
 * <p>As we find first the offset, it's not needed to know the internal structure of the .pack file,
 * just how to read the git object.
 *
 * <p>You can find further information about the internal pack file structure here:
 * http://shafiul.github.io/gitbook/7_the_packfile.html
 *
 * <p>http://driusan.github.io/git-pack.html
 */
public class V2PackGitInfoExtractor extends VersionedPackGitInfoExtractor {

  public static final short VERSION = 2;

  private static final int[] INVALID_TYPE_AND_SIZE = new int[] {-1, -1};

  @Override
  public short getVersion() {
    return VERSION;
  }

  /**
   * Extracts the Git object information of a certain commit sha from the IDX and pack files.
   *
   * <p>If there is an error in the process, the object contains a flag called "error" set to true.
   *
   * @param idxFile
   * @param packFile
   * @param commitSha
   * @return gitPackObject with type and deflated content.
   */
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
        seek(idx, 4L * previousIndex, SeekOrigin.CURRENT);
        numObjectsPreviousIndex = idx.readInt();
      }

      // In the fanout table, every index has its objects + the previous ones.
      // We need to subtract the previous index objects to know the correct
      // actual number of objects for this specific index.
      final int numObjectsIndex = idx.readInt() - numObjectsPreviousIndex;

      // Seek to last position. The last position contains the number of all objects.
      seek(idx, 4L * (255 - (index + 1)), SeekOrigin.CURRENT);
      final int totalObjects = idx.readInt();

      // Search the sha index in the second layer: the SHA listing.
      final int shaIndex =
          searchSha(idx, commitSha, totalObjects, numObjectsPreviousIndex, numObjectsIndex);
      if (shaIndex == NOT_FOUND_SHA_INDEX) {
        return NOT_FOUND_PACK_OBJECT;
      }

      // Third layer: 4 byte CRC for each object. We skip it.
      seek(idx, 4L * totalObjects, SeekOrigin.CURRENT);

      // Search packOffset in fourth and fifth layer.
      final long packOffset = searchOffset(idx, shaIndex, totalObjects);

      // Open pack file and seek to packOffset.
      final RandomAccessFile pack = new RandomAccessFile(packFile, "r");
      seek(pack, packOffset, SeekOrigin.BEGIN);

      // Get the type and the size of the git object.
      final int[] gitObjectTypeAndSize = extractGitObjectTypeAndSize(pack);
      if (Arrays.equals(gitObjectTypeAndSize, INVALID_TYPE_AND_SIZE)) {
        return ERROR_PACK_OBJECT;
      }

      // Return the GitPackObject with the extracted information.
      return new GitPackObject(
          shaIndex,
          (byte) gitObjectTypeAndSize[TYPE_INDEX],
          readBytes(pack, gitObjectTypeAndSize[SIZE_INDEX]),
          false);
    } catch (final Exception e) {
      return ERROR_PACK_OBJECT;
    }
  }

  /**
   * Extracts the SHA index using the number of objects found in the fanout table.
   *
   * @param idx
   * @param commitSha
   * @param totalObjects
   * @param numObjectsPreviousIndex
   * @param numObjectsIndex
   * @return sha index to be used in the offsets table.
   * @throws IOException
   */
  protected int searchSha(
      final RandomAccessFile idx,
      final String commitSha,
      final int totalObjects,
      final int numObjectsPreviousIndex,
      final int numObjectsIndex)
      throws IOException {

    // Skip all previous SHAs
    seek(idx, 20L * (numObjectsPreviousIndex), GitPackUtils.SeekOrigin.CURRENT);
    final byte[] shaBytes = hexToByteArray(commitSha);

    // Search target SHA index in the SHA listing table.
    for (int i = 0; i < numObjectsIndex; i++) {
      final byte[] current = readBytes(idx, 20);
      if (Arrays.equals(shaBytes, current)) {
        final int shaIndex = numObjectsPreviousIndex + i;

        // If we find the SHA, we skip all SHA listing table.
        seek(idx, 20L * (totalObjects - (shaIndex + 1)), SeekOrigin.CURRENT);
        return shaIndex;
      }
    }

    return NOT_FOUND_SHA_INDEX;
  }

  /**
   * Find the offset in the fourth and fifth layer of the IDX file using the sha index.
   *
   * @param idx
   * @param shaIndex
   * @param totalObjects
   * @return
   * @throws IOException
   */
  protected long searchOffset(
      final RandomAccessFile idx, final int shaIndex, final int totalObjects) throws IOException {
    // Fourth layer: 4 byte per object of offset in pack file
    seek(idx, 4L * shaIndex, SeekOrigin.CURRENT);
    int offset = idx.readInt();

    // Check the first bit.
    // If the first bit == 0, the offset is in the fourth layer.
    // If the first bit == 1, the offset is in the fifth layer. (Only in pack files larger than 2
    // Gb)
    if (((offset >> 31) & 1) == 0) {
      return offset;
    } else {
      // Clear first bit and look at it at the 5th layer
      offset &= 0x7FFFFFFF;
      // Skip complete fourth layer.
      seek(idx, 4L * (totalObjects - (shaIndex + 1)), SeekOrigin.CURRENT);
      // Use the offset from fourth layer, to find the actual pack file offset in the fifth layer.
      // In this case, the offset is 8 bytes long.
      seek(idx, 8L * offset, SeekOrigin.CURRENT);
      return idx.readLong();
    }
  }

  /**
   * Returns an int array with the type and size of the git object. The type is stored in the pos 0
   * of the array The size is stored in the pos 1 of the array.
   *
   * @param pack
   * @return type and size of the git object.
   * @throws IOException
   */
  protected int[] extractGitObjectTypeAndSize(final RandomAccessFile pack) throws IOException {
    // The type and size of the git object is stored in a variable length byte array.
    // This makes sense because a blob object can have an arbitrary size,
    // but for our use case, we're only interested in commits message (or tags).
    // We consider that 2 bytes is more than enough to store the commit message.
    final byte[] sizeParts = readBytes(pack, 2);

    // If the second byte has the first bit == 1,
    // it means that the size was stored in more than 2 bytes.
    // so return invalid type and size.
    if (((sizeParts[1] >> 7) & 1) == 1) {
      return INVALID_TYPE_AND_SIZE;
    }

    // First bit indicates if the size continues in the following byte or not.
    // Next 3 bits are used to indicate the Git object type:
    // https://git-scm.com/docs/pack-format#_object_types

    // If type is not commit or tag, we consider it invalid.
    final byte type = (byte) ((sizeParts[0] & 0x70) >> 4);
    if (type != COMMIT_TYPE && type != TAG_TYPE) {
      return INVALID_TYPE_AND_SIZE;
    }

    // We build the size combining the bits from sizeParts
    // using bitwise operations (BigEndian).
    // Example:
    // - sizeParts = [-100, 53] => [10011100, 00110101]
    // - size = 00000000 00000000 00000000 00000000
    int size = 0;

    // Clean first bit and add bits to size using OR.
    //    size       00000000 00000000 00000000 00000000
    // OR sizePart[1] & 0x7F                    00110101
    //    size       00000000 00000000 00000000 00110101
    size |= (sizeParts[1] & 0x7F);

    // Move 4 bits to the left.
    // size 00000000 00000000 00000011 01010000
    size <<= 4;

    // Clean four initial bits and add the result to size using OR.
    // size            00000000 00000000 00000011 01010000
    // OR sizePart[0] & 0x0F                      00001100
    // size            00000000 00000000 00000011 01011100
    size |= (sizeParts[0] & 0x0F);

    // Finally, in the example:
    // type: 001 -> commit
    // size: 860
    return new int[] {type, size};
  }
}
