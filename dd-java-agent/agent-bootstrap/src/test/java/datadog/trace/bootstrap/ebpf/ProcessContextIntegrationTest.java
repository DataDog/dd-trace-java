package datadog.trace.bootstrap.ebpf;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import datadog.environment.OperatingSystem;
import datadog.trace.api.Config;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Integration test for ProcessContext class that validates eBPF memory mapping functionality on
 * Linux systems.
 *
 * <p>This test searches through /proc/self/maps to find anonymous memory mappings, locates the
 * ProcessContext memory region containing MAGIC bytes, and validates that the pointer after MAGIC
 * bytes points to a valid memory region.
 */
class ProcessContextIntegrationTest {
  private static final Logger log = LoggerFactory.getLogger(ProcessContextIntegrationTest.class);
  private static final String MAGIC = "OTL-PROC";
  private static final byte[] MAGIC_BYTES = MAGIC.getBytes();
  private static final Pattern MAPS_PATTERN =
      Pattern.compile(
          "([0-9a-f]+)-([0-9a-f]+)\\s+(r-xp|r--p|rw-p|rwxp)\\s+[0-9a-f]+\\s+[0-9a-f]+:[0-9a-f]+\\s+[0-9]+\\s*(.*)");

  @Test  
  void testProcessContextBasicFunctionality() throws IOException {
    assumeTrue(OperatingSystem.isLinux());
    // First, publish process context to create the memory mapping
    Config config = Config.get();
    ProcessContext.publish(config);
    log.info("ProcessContext.publish() called successfully");

    testProcessContextMemoryMappingIntegrationImpl();
  }

  @Test
  void testProcessContextMemoryMappingIntegration() throws IOException {
    assumeTrue(OperatingSystem.isLinux());
    testProcessContextMemoryMappingIntegrationImpl();
  }
  
  private void testProcessContextMemoryMappingIntegrationImpl() throws IOException {
    assumeTrue(OperatingSystem.isLinux());
    // First, publish process context to create the memory mapping
    Config config = Config.get();
    ProcessContext.publish(config);

    // Give the system a moment to ensure the memory is mapped
    try {
      Thread.sleep(100);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    List<MemoryRegion> anonymousRegions = parseMemoryMaps();
    log.info("Found {} anonymous memory regions", anonymousRegions.size());

    // Find regions that are exactly 16 bytes (ProcessContext buffer size)
    List<MemoryRegion> candidateRegions =
        anonymousRegions.stream().filter(region -> region.size == 16).collect(Collectors.toList());

    log.info("Found {} anonymous regions of size 16 bytes", candidateRegions.size());

    MemoryRegion magicRegion = null;
    long pointerValue = 0;

    // Search for MAGIC bytes in candidate regions
    for (MemoryRegion region : candidateRegions) {
      try {
        byte[] regionData = readMemoryRegion(region);
        if (regionData != null && containsMagicBytes(regionData)) {
          magicRegion = region;
          // Extract the 8-byte pointer that follows the MAGIC bytes
          pointerValue = extractPointerAfterMagic(regionData);
          log.info(
              "Found MAGIC bytes in region {}. Pointer value: 0x{}",
              region,
              Long.toHexString(pointerValue));
          break;
        }
      } catch (Exception e) {
        log.debug("Could not read region {}: {}", region, e.getMessage());
      }
    }

    assertNotNull(magicRegion, "Should find a memory region containing MAGIC bytes");
    assertTrue(pointerValue > 0, "Pointer value after MAGIC bytes should be non-zero");

    // Validate that the pointer points to a valid memory region
    MemoryRegion targetRegion = findMemoryRegionContainingAddress(anonymousRegions, pointerValue);
    assertNotNull(
        targetRegion,
        String.format(
            "Pointer 0x%x should point to a valid anonymous memory region", pointerValue));

    log.info(
        "Successfully validated ProcessContext memory mapping: MAGIC region {} -> pointer 0x{} -> target region {}",
        magicRegion,
        Long.toHexString(pointerValue),
        targetRegion);

    // Additional validation: ensure the target region is large enough to contain payload data
    assertTrue(
        targetRegion.size >= 1024,
        "Target region should be at least 1024 bytes (ProcessContext payload size)");
  }

  private List<MemoryRegion> parseMemoryMaps() throws IOException {
    List<MemoryRegion> regions = new ArrayList<>();

    try (BufferedReader reader = Files.newBufferedReader(Paths.get("/proc/self/maps"))) {
      String line;
      while ((line = reader.readLine()) != null) {
        Matcher matcher = MAPS_PATTERN.matcher(line);
        if (matcher.matches()) {
          long start = Long.parseUnsignedLong(matcher.group(1), 16);
          long end = Long.parseUnsignedLong(matcher.group(2), 16);
          String permissions = matcher.group(3);
          String path = matcher.group(4).trim();

          // Look for anonymous mappings (empty path or special markers)
          if (path.isEmpty() || path.startsWith("[") || path.equals("(deleted)")) {
            regions.add(new MemoryRegion(start, end, permissions, path));
          }
        }
      }
    }

    return regions;
  }

  private byte[] readMemoryRegion(MemoryRegion region) {
    if (!region.permissions.contains("r")) {
      return null; // Cannot read non-readable regions
    }

    try (RandomAccessFile memFile = new RandomAccessFile("/proc/self/mem", "r")) {
      byte[] buffer = new byte[(int) region.size];
      memFile.seek(region.start);
      int bytesRead = memFile.read(buffer);

      if (bytesRead == buffer.length) {
        return buffer;
      }
    } catch (IOException e) {
      log.debug("Failed to read memory region {}: {}", region, e.getMessage());
    }

    return null;
  }

  private boolean containsMagicBytes(byte[] data) {
    if (data.length < MAGIC_BYTES.length) {
      return false;
    }

    for (int i = 0; i <= data.length - MAGIC_BYTES.length; i++) {
      boolean found = true;
      for (int j = 0; j < MAGIC_BYTES.length; j++) {
        if (data[i + j] != MAGIC_BYTES[j]) {
          found = false;
          break;
        }
      }
      if (found) {
        return true;
      }
    }

    return false;
  }

  private long extractPointerAfterMagic(byte[] data) {
    // Find MAGIC bytes position
    for (int i = 0; i <= data.length - MAGIC_BYTES.length - 8; i++) {
      boolean found = true;
      for (int j = 0; j < MAGIC_BYTES.length; j++) {
        if (data[i + j] != MAGIC_BYTES[j]) {
          found = false;
          break;
        }
      }
      if (found) {
        // Extract 8-byte long value after MAGIC bytes (little-endian)
        long value = 0;
        for (int k = 0; k < 8; k++) {
          value |= ((long) (data[i + MAGIC_BYTES.length + k] & 0xFF)) << (k * 8);
        }
        return value;
      }
    }
    return 0;
  }

  private MemoryRegion findMemoryRegionContainingAddress(List<MemoryRegion> regions, long address) {
    return regions.stream()
        .filter(region -> address >= region.start && address < region.end)
        .findFirst()
        .orElse(null);
  }

  private static class MemoryRegion {
    final long start;
    final long end;
    final long size;
    final String permissions;
    final String path;

    MemoryRegion(long start, long end, String permissions, String path) {
      this.start = start;
      this.end = end;
      this.size = end - start;
      this.permissions = permissions;
      this.path = path;
    }

    @Override
    public String toString() {
      return String.format("%x-%x (%d bytes) %s %s", start, end, size, permissions, path);
    }
  }
}
