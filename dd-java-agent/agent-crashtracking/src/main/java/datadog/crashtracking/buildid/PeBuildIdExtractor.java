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
 * Extracts build IDs from PE (Portable Executable) binaries used on Windows. Uses the GUID and Age
 * from the PDB70 CodeView debug information.
 */
public class PeBuildIdExtractor implements BuildIdExtractor {
  private static final Logger log = LoggerFactory.getLogger(PeBuildIdExtractor.class);

  // DOS header magic: 'M' 'Z'
  private static final byte[] MZ_MAGIC = {0x4d, 0x5a};

  // PE signature: 'P' 'E' 0x00 0x00
  private static final byte[] PE_SIGNATURE = {0x50, 0x45, 0x00, 0x00};

  // PDB70 CodeView signature: 'R' 'S' 'D' 'S'
  private static final int PDB70_SIGNATURE = 0x53445352;

  // Debug directory type for CodeView
  private static final int IMAGE_DEBUG_TYPE_CODEVIEW = 2;

  // PE32 magic
  private static final short PE32_MAGIC = 0x10b;

  // PE32+ (64-bit) magic
  private static final short PE32_PLUS_MAGIC = 0x20b;

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

      // 4. Read COFF header
      // COFF header starts right after PE signature
      // Layout:
      //   +0: Machine (2 bytes)
      //   +2: NumberOfSections (2 bytes)
      //   +4: TimeDateStamp (4 bytes)
      //   +8: PointerToSymbolTable (4 bytes)
      //   +12: NumberOfSymbols (4 bytes)
      //   +16: SizeOfOptionalHeader (2 bytes)
      //   +18: Characteristics (2 bytes)
      long coffHeaderOffset = peOffset + 4;

      if (coffHeaderOffset + 20 > size) {
        log.debug("Invalid COFF header position in file: {}", file);
        return null;
      }

      raf.seek(coffHeaderOffset + 16);
      byte[] sizeBytes = new byte[2];
      if (raf.read(sizeBytes) != 2) {
        log.debug("Failed to read SizeOfOptionalHeader from: {}", file);
        return null;
      }
      buf = ByteBuffer.wrap(sizeBytes);
      buf.order(ByteOrder.LITTLE_ENDIAN);
      int sizeOfOptionalHeader = buf.getShort() & 0xFFFF;

      if (sizeOfOptionalHeader == 0) {
        log.debug("No optional header in PE file: {}", file);
        return null;
      }

      // 5. Read Optional Header to get Debug Directory RVA and size
      // Optional header starts right after COFF header (20 bytes after PE signature)
      long optionalHeaderOffset = coffHeaderOffset + 20;

      if (optionalHeaderOffset + 2 > size) {
        log.debug("Invalid optional header position in file: {}", file);
        return null;
      }

      raf.seek(optionalHeaderOffset);
      byte[] magicBytes = new byte[2];
      if (raf.read(magicBytes) != 2) {
        log.debug("Failed to read optional header magic from: {}", file);
        return null;
      }
      buf = ByteBuffer.wrap(magicBytes);
      buf.order(ByteOrder.LITTLE_ENDIAN);
      short optionalMagic = buf.getShort();

      boolean is64Bit = (optionalMagic == PE32_PLUS_MAGIC);

      // Debug directory is in the data directories array
      // For PE32: offset 96 from optional header start
      // For PE32+: offset 112 from optional header start
      long debugDirOffset = optionalHeaderOffset + (is64Bit ? 112 : 96) + (6 * 8); // 6th entry
      // Each data directory entry is 8 bytes: RVA (4) + Size (4)

      if (debugDirOffset + 8 > size) {
        log.debug("Invalid debug directory offset in file: {}", file);
        return null;
      }

      raf.seek(debugDirOffset);
      byte[] debugDirData = new byte[8];
      if (raf.read(debugDirData) != 8) {
        log.debug("Failed to read debug directory data from: {}", file);
        return null;
      }
      buf = ByteBuffer.wrap(debugDirData);
      buf.order(ByteOrder.LITTLE_ENDIAN);
      long debugDirRva = buf.getInt() & 0xFFFFFFFFL;
      long debugDirSize = buf.getInt() & 0xFFFFFFFFL;

      if (debugDirRva == 0 || debugDirSize == 0) {
        log.debug("No debug directory in PE file: {}", file);
        return null;
      }

      // 6. Convert RVA to file offset by reading section headers
      long sectionHeadersOffset = optionalHeaderOffset + sizeOfOptionalHeader;
      long debugDirFileOffset =
          rvaToFileOffset(raf, sectionHeadersOffset, coffHeaderOffset, debugDirRva, size);

      if (debugDirFileOffset == -1) {
        log.debug("Failed to convert debug directory RVA to file offset: {}", file);
        return null;
      }

      // 7. Parse debug directory entries to find CodeView entry
      int numEntries = (int) (debugDirSize / 28); // Each debug directory entry is 28 bytes
      for (int i = 0; i < numEntries; i++) {
        long entryOffset = debugDirFileOffset + (i * 28);
        if (entryOffset + 28 > size) {
          continue;
        }

        raf.seek(entryOffset);
        byte[] entryData = new byte[28];
        if (raf.read(entryData) != 28) {
          continue;
        }

        buf = ByteBuffer.wrap(entryData);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        buf.getInt(); // Characteristics
        buf.getInt(); // TimeDateStamp
        buf.getShort(); // MajorVersion
        buf.getShort(); // MinorVersion
        int type = buf.getInt();
        int dataSize = buf.getInt();
        long dataRva = buf.getInt() & 0xFFFFFFFFL;
        long dataFilePointer = buf.getInt() & 0xFFFFFFFFL;

        if (type == IMAGE_DEBUG_TYPE_CODEVIEW && dataSize > 0) {
          // Found CodeView entry, read PDB70 structure
          if (dataFilePointer + dataSize > size) {
            log.debug("Invalid CodeView data pointer in file: {}", file);
            continue;
          }

          raf.seek(dataFilePointer);
          byte[] cvData = new byte[Math.min(dataSize, 24)]; // PDB70 header is 24 bytes
          if (raf.read(cvData) != cvData.length) {
            continue;
          }

          buf = ByteBuffer.wrap(cvData);
          buf.order(ByteOrder.LITTLE_ENDIAN);

          int signature = buf.getInt();
          if (signature != PDB70_SIGNATURE) {
            continue;
          }

          // Read GUID (16 bytes)
          long guidData1 = buf.getInt() & 0xFFFFFFFFL;
          int guidData2 = buf.getShort() & 0xFFFF;
          int guidData3 = buf.getShort() & 0xFFFF;
          byte[] guidData4 = new byte[8];
          buf.get(guidData4);

          // Read Age (4 bytes)
          long age = buf.getInt() & 0xFFFFFFFFL;

          // Format as GUID + Age in hex (like dotnet tracer)
          return formatBuildId(guidData1, guidData2, guidData3, guidData4, age);
        }
      }

      log.debug("No CodeView debug information found in PE file: {}", file);
      return null;

    } catch (IOException | SecurityException e) {
      log.debug("Failed to extract PE build ID from {}: {}", file, e.getMessage());
      return null;
    } catch (Throwable t) {
      log.debug("Unexpected error extracting PE build ID from {}: {}", file, t.getMessage());
      return null;
    }
  }

  private long rvaToFileOffset(
      RandomAccessFile raf,
      long sectionHeadersOffset,
      long coffHeaderOffset,
      long rva,
      long fileSize)
      throws IOException {
    // Read number of sections from COFF header
    raf.seek(coffHeaderOffset + 2);
    byte[] numSectionsBytes = new byte[2];
    if (raf.read(numSectionsBytes) != 2) {
      return -1;
    }
    ByteBuffer buf = ByteBuffer.wrap(numSectionsBytes);
    buf.order(ByteOrder.LITTLE_ENDIAN);
    int numSections = buf.getShort() & 0xFFFF;

    // Each section header is 40 bytes
    for (int i = 0; i < numSections; i++) {
      long sectionOffset = sectionHeadersOffset + (i * 40);
      if (sectionOffset + 40 > fileSize) {
        continue;
      }

      raf.seek(sectionOffset + 8); // Skip name (8 bytes)
      byte[] sectionData = new byte[16];
      if (raf.read(sectionData) != 16) {
        continue;
      }

      buf = ByteBuffer.wrap(sectionData);
      buf.order(ByteOrder.LITTLE_ENDIAN);

      long virtualSize = buf.getInt() & 0xFFFFFFFFL;
      long virtualAddress = buf.getInt() & 0xFFFFFFFFL;
      @SuppressWarnings("unused")
      long sizeOfRawData = buf.getInt() & 0xFFFFFFFFL;
      long pointerToRawData = buf.getInt() & 0xFFFFFFFFL;

      // Check if RVA falls within this section
      if (rva >= virtualAddress && rva < virtualAddress + virtualSize) {
        return pointerToRawData + (rva - virtualAddress);
      }
    }

    return -1;
  }

  private String formatBuildId(
      long guidData1, int guidData2, int guidData3, byte[] guidData4, long age) {
    // Format: GUID (uppercase, without dashes) + Age (lowercase hex)
    return String.format(
        "%08X%04X%04X%02X%02X%02X%02X%02X%02X%02X%02X%x",
        guidData1,
        guidData2,
        guidData3,
        guidData4[0] & 0xFF,
        guidData4[1] & 0xFF,
        guidData4[2] & 0xFF,
        guidData4[3] & 0xFF,
        guidData4[4] & 0xFF,
        guidData4[5] & 0xFF,
        guidData4[6] & 0xFF,
        guidData4[7] & 0xFF,
        age);
  }

  @Override
  public BuildInfo.FileType fileType() {
    return BuildInfo.FileType.PE;
  }

  @Override
  public BuildInfo.BuildIdType buildIdType() {
    return BuildInfo.BuildIdType.PDB;
  }
}
