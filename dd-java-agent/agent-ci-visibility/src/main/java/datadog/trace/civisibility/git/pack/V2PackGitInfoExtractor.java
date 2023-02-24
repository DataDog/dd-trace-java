package datadog.trace.civisibility.git.pack;

import static datadog.trace.civisibility.git.GitObject.COMMIT_TYPE;
import static datadog.trace.civisibility.git.GitObject.TAG_TYPE;
import static datadog.trace.civisibility.git.pack.GitPackUtils.hexToByteArray;
import static datadog.trace.civisibility.git.pack.GitPackUtils.readBytes;

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
  private static final int MAX_ALLOWED_SIZE = Character.MAX_VALUE; // 65535 or 2 bytes

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

      try (final RandomAccessFile idx = new RandomAccessFile(idxFile, "r")) {
        // Skip header and version
        idx.seek(8);

        int numObjectsPreviousIndex = 0;
        if (previousIndex != -1) {
          // Seek to previousIndex position.
          idx.seek((4L * previousIndex) + idx.getFilePointer());
          numObjectsPreviousIndex = idx.readInt();
        }

        // In the fanout table, every index has its objects + the previous ones.
        // We need to subtract the previous index objects to know the correct
        // actual number of objects for this specific index.
        final int numObjectsIndex = idx.readInt() - numObjectsPreviousIndex;

        // Seek to last position. The last position contains the number of all objects.
        idx.seek((4L * (255 - (index + 1))) + idx.getFilePointer());
        final int totalObjects = idx.readInt();

        // Search the sha index in the second layer: the SHA listing.
        final int shaIndex =
            searchSha(idx, commitSha, totalObjects, numObjectsPreviousIndex, numObjectsIndex);
        if (shaIndex == GitPackObject.NOT_FOUND_SHA_INDEX) {
          return GitPackObject.NOT_FOUND_PACK_OBJECT;
        }

        // Third layer: 4 byte CRC for each object. We skip it.
        idx.seek((4L * totalObjects) + idx.getFilePointer());

        // Search packOffset in fourth and fifth layer.
        final long packOffset = searchOffset(idx, shaIndex, totalObjects);

        // Open pack file and seek to packOffset.
        try (final RandomAccessFile pack = new RandomAccessFile(packFile, "r")) {
          pack.seek(packOffset);

          // Get the type and the size of the git object.
          final int[] gitObjectTypeAndSize = extractGitObjectTypeAndSize(pack);
          if (Arrays.equals(gitObjectTypeAndSize, INVALID_TYPE_AND_SIZE)) {
            return GitPackObject.ERROR_PACK_OBJECT;
          }

          // Return the GitPackObject with the extracted information.
          return new GitPackObject(
              shaIndex,
              (byte) gitObjectTypeAndSize[TYPE_INDEX],
              readBytes(pack, gitObjectTypeAndSize[SIZE_INDEX]),
              false);
        }
      }

    } catch (final Exception e) {
      return GitPackObject.ERROR_PACK_OBJECT;
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
    idx.seek((20L * numObjectsPreviousIndex) + idx.getFilePointer());
    final byte[] shaBytes = hexToByteArray(commitSha);

    // Search target SHA index in the SHA listing table.
    for (int i = 0; i < numObjectsIndex; i++) {
      final byte[] current = readBytes(idx, 20);
      if (Arrays.equals(shaBytes, current)) {
        final int shaIndex = numObjectsPreviousIndex + i;

        // If we find the SHA, we skip all SHA listing table.
        idx.seek((20L * (totalObjects - (shaIndex + 1))) + idx.getFilePointer());
        return shaIndex;
      }
    }

    return GitPackObject.NOT_FOUND_SHA_INDEX;
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
    idx.seek((4L * shaIndex) + idx.getFilePointer());
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
      idx.seek(4L * (totalObjects - (shaIndex + 1)) + idx.getFilePointer());
      // Use the offset from fourth layer, to find the actual pack file offset in the fifth layer.
      // In this case, the offset is 8 bytes long.
      idx.seek((8L * offset) + idx.getFilePointer());
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
    // If the read byte has the first bit == 0, it means it's the final byte to read.
    byte sizePart;
    byte[] sizeParts = new byte[2]; // 2 bytes size is the most common use case.
    int idx = 0;
    do {
      sizePart = readBytes(pack, 1)[0];
      sizeParts[idx++] = sizePart;

      if (idx == sizeParts.length && ((sizePart >> 7) & 1) == 1) {
        final byte[] temp = new byte[sizeParts.length + 1];
        System.arraycopy(sizeParts, 0, temp, 0, sizeParts.length);
        sizeParts = temp;
      }

    } while (((sizePart >> 7) & 1) == 1);

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

    // Clean first bit and add bits to size using OR.
    //    size       00000000 00000000 00000000 00000000
    // OR sizePart.get(1) & 0x7F                00110101
    //    size       00000000 00000000 00000000 00110101

    // Move 4 bits to the left.
    // size 00000000 00000000 00000011 01010000

    // Clean four initial bits and add the result to size using OR.
    // size            00000000 00000000 00000011 01010000
    // OR sizePart.get(0) & 0x0F                  00001100
    // size            00000000 00000000 00000011 01011100

    // Finally, in the example:
    // type: 001 -> commit
    // size: 860

    int size = 0;
    for (int i = (sizeParts.length - 1); i >= 0; i--) {
      if (i == 0) {
        // The first part contains also the 3 bits for the type,
        // so we move only 4 bits to the left.
        size <<= 4;
        size |= (sizeParts[i] & 0x0F);
      } else {
        // The rest of the parts don't have the 3 bits for the type,
        // so we move 7 bits to the left.
        size <<= 7;
        size |= (sizeParts[i] & 0x7F);
      }
    }

    // As the size can be any number, we need to protect ourselves
    // to avoid reading potentially huge Git objects.
    // We consider that 2 bytes is more than enough to store the commit message.
    if (size > MAX_ALLOWED_SIZE) {
      return INVALID_TYPE_AND_SIZE;
    }

    return new int[] {type, size};
  }
}
