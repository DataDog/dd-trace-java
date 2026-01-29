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
 * Extracts build IDs from ELF (Executable and Linkable Format) binaries. Supports both 32-bit and
 * 64-bit ELF files with little-endian and big-endian byte ordering.
 */
public class ElfBuildIdExtractor implements BuildIdExtractor {
  private static final Logger log = LoggerFactory.getLogger(ElfBuildIdExtractor.class);

  // ELF magic: 0x7f 'E' 'L' 'F'
  private static final byte[] ELF_MAGIC = {0x7f, 0x45, 0x4c, 0x46};

  // ELF header constants
  private static final int ELFCLASS32 = 1;
  private static final int ELFCLASS64 = 2;

  private static final int ELFDATA2LSB = 1; // Little endian
  private static final int ELFDATA2MSB = 2; // Big endian

  // Program header constants
  private static final int PT_NOTE = 4;

  // Note header constants
  private static final int NT_GNU_BUILD_ID = 3;
  private static final byte[] GNU_NOTE_NAME = "GNU\0".getBytes();

  @Override
  public String extractBuildId(Path file) {
    try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
      // 1. Read and verify ELF magic (first 4 bytes)
      byte[] magic = new byte[4];
      if (raf.read(magic) != 4 || !Arrays.equals(magic, ELF_MAGIC)) {
        log.debug("Not an ELF file: {}", file);
        return null;
      }

      // 2. Determine file class (32 or 64 bit)
      int elfClass = raf.read();
      boolean is64Bit = (elfClass == ELFCLASS64);
      if (elfClass != ELFCLASS32 && elfClass != ELFCLASS64) {
        log.debug("Invalid ELF class in file: {}", file);
        return null;
      }

      // 3. Determine endianness
      int elfData = raf.read();
      boolean isLittleEndian = (elfData == ELFDATA2LSB);
      if (elfData != ELFDATA2LSB && elfData != ELFDATA2MSB) {
        log.debug("Invalid ELF data encoding in file: {}", file);
        return null;
      }

      // 4. Read ELF header to get program header table offset and size
      raf.seek(is64Bit ? 32 : 28); // Offset to e_phoff
      long phoff = is64Bit ? readLong(raf, isLittleEndian) : readInt(raf, isLittleEndian);

      raf.seek(is64Bit ? 54 : 42); // Offset to e_phnum
      int phnum = readShort(raf, isLittleEndian);

      // 5. Iterate through program headers to find PT_NOTE segments
      for (int i = 0; i < phnum; i++) {
        long phEntryOffset = phoff + ((long) i * (is64Bit ? 56 : 32));
        raf.seek(phEntryOffset);

        int pType = (int) readInt(raf, isLittleEndian);
        if (pType != PT_NOTE) {
          continue;
        }

        // Read PT_NOTE segment offset and size
        long pOffset, pSize;
        if (is64Bit) {
          raf.seek(phEntryOffset + 8);
          pOffset = readLong(raf, isLittleEndian);
          raf.seek(phEntryOffset + 32);
          pSize = readLong(raf, isLittleEndian);
        } else {
          raf.seek(phEntryOffset + 4);
          pOffset = readInt(raf, isLittleEndian);
          raf.seek(phEntryOffset + 16);
          pSize = readInt(raf, isLittleEndian);
        }

        // 6. Parse notes in this PT_NOTE segment
        String buildId = parseNoteSegment(raf, pOffset, pSize, isLittleEndian);
        if (buildId != null) {
          return buildId;
        }
      }

      log.debug("No build ID found in ELF file: {}", file);
      return null;

    } catch (IOException | SecurityException e) {
      log.debug("Failed to extract ELF build ID from {}: {}", file, e.getMessage());
      return null;
    } catch (Throwable t) {
      log.debug("Unexpected error extracting ELF build ID from {}: {}", file, t.getMessage());
      return null;
    }
  }

  @Override
  public BuildInfo.FileType fileType() {
    return BuildInfo.FileType.ELF;
  }

  @Override
  public BuildInfo.BuildIdType buildIdType() {
    return BuildInfo.BuildIdType.SHA1;
  }

  private String parseNoteSegment(
      RandomAccessFile raf, long offset, long size, boolean isLittleEndian) throws IOException {
    raf.seek(offset);
    long end = offset + size;

    while (raf.getFilePointer() < end) {
      long currentPos = raf.getFilePointer();

      // Ensure we don't read beyond segment
      if (currentPos + 12 > end) {
        break;
      }

      int namesz = (int) readInt(raf, isLittleEndian);
      int descsz = (int) readInt(raf, isLittleEndian);
      int type = (int) readInt(raf, isLittleEndian);

      // Align to 4-byte boundary
      int nameLen = (namesz + 3) & ~3;
      int descLen = (descsz + 3) & ~3;

      // Bounds check
      if (currentPos + 12 + nameLen + descLen > end) {
        break;
      }

      // Read note name
      byte[] name = new byte[namesz];
      if (raf.read(name) != namesz) {
        throw new IOException("Failed to read note name");
      }
      int skipped = raf.skipBytes(nameLen - namesz);
      if (skipped != nameLen - namesz) {
        throw new IOException("Failed to skip padding after note name");
      }

      // Check if this is the GNU build ID note
      if (type == NT_GNU_BUILD_ID && Arrays.equals(name, GNU_NOTE_NAME)) {
        // Read build ID
        byte[] buildIdBytes = new byte[descsz];
        if (raf.read(buildIdBytes) != descsz) {
          throw new IOException("Failed to read build ID");
        }

        // Convert to hex string
        StringBuilder hex = new StringBuilder(descsz * 2);
        for (byte b : buildIdBytes) {
          hex.append(String.format("%02x", b & 0xff));
        }
        return hex.toString();
      } else {
        // Skip descriptor
        skipped = raf.skipBytes(descLen);
        if (skipped != descLen) {
          throw new IOException("Failed to skip descriptor");
        }
      }
    }
    return null;
  }

  private int readShort(RandomAccessFile raf, boolean isLittleEndian) throws IOException {
    byte[] bytes = new byte[2];
    if (raf.read(bytes) != 2) {
      throw new IOException("Failed to read short");
    }
    ByteBuffer buf = ByteBuffer.wrap(bytes);
    buf.order(isLittleEndian ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
    return buf.getShort() & 0xFFFF;
  }

  private long readInt(RandomAccessFile raf, boolean isLittleEndian) throws IOException {
    byte[] bytes = new byte[4];
    if (raf.read(bytes) != 4) {
      throw new IOException("Failed to read int");
    }
    ByteBuffer buf = ByteBuffer.wrap(bytes);
    buf.order(isLittleEndian ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
    return buf.getInt() & 0xFFFFFFFFL;
  }

  private long readLong(RandomAccessFile raf, boolean isLittleEndian) throws IOException {
    byte[] bytes = new byte[8];
    if (raf.read(bytes) != 8) {
      throw new IOException("Failed to read long");
    }
    ByteBuffer buf = ByteBuffer.wrap(bytes);
    buf.order(isLittleEndian ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
    return buf.getLong();
  }
}
