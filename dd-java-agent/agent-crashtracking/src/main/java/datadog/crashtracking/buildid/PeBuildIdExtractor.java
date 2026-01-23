package datadog.crashtracking.buildid;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Extracts build IDs from PE (Portable Executable) binaries used on Windows. Uses the TimeDateStamp
 * from the COFF header as the build ID.
 */
public class PeBuildIdExtractor implements BuildIdExtractor {
  private static final Logger log = LoggerFactory.getLogger(PeBuildIdExtractor.class);

  // DOS header magic: 'M' 'Z'
  private static final byte[] MZ_MAGIC = {0x4d, 0x5a};

  // PE signature: 'P' 'E' 0x00 0x00
  private static final byte[] PE_SIGNATURE = {0x50, 0x45, 0x00, 0x00};

  @Override
  public String extractBuildId(Path file) {
    try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
      long size = raf.length();

      // 1. Verify DOS header magic (first 2 bytes)
      byte[] magic = new byte[2];
      if (raf.read(magic) != 2 || !Arrays.equals(magic, MZ_MAGIC)) {
        log.debug("Not a PE file (missing MZ magic): {}", file);
        return null;
      }

      // 2. Read PE header offset from DOS header (at offset 0x3C)
      raf.seek(0x3C);
      byte[] offsetBytes = new byte[4];
      if (raf.read(offsetBytes) != 4) {
        log.debug("Failed to read PE header offset from: {}", file);
        return null;
      }
      ByteBuffer buf = ByteBuffer.wrap(offsetBytes);
      buf.order(ByteOrder.LITTLE_ENDIAN); // PE is always little-endian
      long peOffset = buf.getInt() & 0xFFFFFFFFL;

      // Bounds check
      if (peOffset > size - 4) {
        log.debug("Invalid PE header offset in file: {}", file);
        return null;
      }

      // 3. Verify PE signature
      raf.seek(peOffset);
      byte[] peSig = new byte[4];
      if (raf.read(peSig) != 4 || !Arrays.equals(peSig, PE_SIGNATURE)) {
        log.debug("Invalid PE signature in file: {}", file);
        return null;
      }

      // 4. Read COFF header TimeDateStamp
      // COFF header starts right after PE signature
      // Layout:
      //   +0: Machine (2 bytes)
      //   +2: NumberOfSections (2 bytes)
      //   +4: TimeDateStamp (4 bytes)
      long coffHeaderOffset = peOffset + 4;

      // Bounds check
      if (coffHeaderOffset + 8 > size) {
        log.debug("Invalid COFF header position in file: {}", file);
        return null;
      }

      raf.seek(coffHeaderOffset + 4); // Skip Machine and NumberOfSections

      byte[] timestampBytes = new byte[4];
      if (raf.read(timestampBytes) != 4) {
        log.debug("Failed to read TimeDateStamp from: {}", file);
        return null;
      }
      buf = ByteBuffer.wrap(timestampBytes);
      buf.order(ByteOrder.LITTLE_ENDIAN);
      long timestamp = buf.getInt() & 0xFFFFFFFFL;

      // 5. Return TimeDateStamp as 8-character hex string
      return String.format("%08x", timestamp);

    } catch (IOException | SecurityException e) {
      log.debug("Failed to extract PE build ID from {}: {}", file, e.getMessage());
      return null;
    } catch (Throwable t) {
      log.debug("Unexpected error extracting PE build ID from {}: {}", file, t.getMessage());
      return null;
    }
  }

  @Override
  public BuildInfo.FileType fileType() {
    return BuildInfo.FileType.PE;
  }

  @Override
  public BuildInfo.BuildIdType buildIdType() {
    return BuildInfo.BuildIdType.PE;
  }
}
