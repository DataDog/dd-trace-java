package com.datadog.profiling.otel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;

/**
 * Command-line interface for converting JFR recordings to OTLP profiles format.
 *
 * <p>Usage:
 *
 * <pre>
 * # Convert single JFR file to protobuf (default)
 * java -cp ... com.datadog.profiling.otel.JfrToOtlpConverterCLI input.jfr output.pb
 *
 * # Convert to JSON format (compact)
 * java -cp ... com.datadog.profiling.otel.JfrToOtlpConverterCLI --json input.jfr output.json
 *
 * # Convert to pretty-printed JSON
 * java -cp ... com.datadog.profiling.otel.JfrToOtlpConverterCLI --json --pretty input.jfr output.json
 *
 * # Include original JFR payload
 * java -cp ... com.datadog.profiling.otel.JfrToOtlpConverterCLI --include-payload input.jfr output.pb
 *
 * # Convert multiple JFR files into single output
 * java -cp ... com.datadog.profiling.otel.JfrToOtlpConverterCLI file1.jfr file2.jfr output.pb
 * </pre>
 */
public class JfrToOtlpConverterCLI {

  public static void main(String[] args) {
    if (args.length < 2) {
      printUsage();
      return;
    }

    try {
      new JfrToOtlpConverterCLI().run(args);
    } catch (Exception e) {
      System.err.println("Error: " + e.getMessage());
      e.printStackTrace();
      throw new RuntimeException("Conversion failed", e);
    }
  }

  private void run(String[] args) throws IOException {
    JfrToOtlpConverter.Kind outputKind = JfrToOtlpConverter.Kind.PROTO;
    boolean includePayload = false;
    boolean prettyPrint = false;
    int firstInputIndex = 0;

    // Parse flags
    while (firstInputIndex < args.length && args[firstInputIndex].startsWith("--")) {
      String flag = args[firstInputIndex];
      switch (flag) {
        case "--json":
          outputKind = JfrToOtlpConverter.Kind.JSON;
          firstInputIndex++;
          break;
        case "--pretty":
          prettyPrint = true;
          firstInputIndex++;
          break;
        case "--include-payload":
          includePayload = true;
          firstInputIndex++;
          break;
        case "--help":
          printUsage();
          return;
        default:
          throw new IllegalArgumentException("Unknown flag: " + flag);
      }
    }

    // Apply pretty-printing to JSON output
    // --pretty implies --json
    if (prettyPrint) {
      outputKind = JfrToOtlpConverter.Kind.JSON_PRETTY;
    }

    // Remaining args: input1.jfr [input2.jfr ...] output.pb/json
    if (args.length - firstInputIndex < 2) {
      throw new IllegalArgumentException("At least one input file and one output file required");
    }

    // Last arg is output file
    Path outputPath = Paths.get(args[args.length - 1]);

    // All other args are input files
    Path[] inputPaths = new Path[args.length - firstInputIndex - 1];
    for (int i = 0; i < inputPaths.length; i++) {
      inputPaths[i] = Paths.get(args[firstInputIndex + i]);
      if (!Files.exists(inputPaths[i])) {
        throw new IOException("Input file not found: " + inputPaths[i]);
      }
    }

    // Perform conversion
    System.out.println("Converting " + inputPaths.length + " JFR file(s) to OTLP format...");
    long startTime = System.currentTimeMillis();

    JfrToOtlpConverter converter = new JfrToOtlpConverter();
    converter.setIncludeOriginalPayload(includePayload);

    // Use current time as recording window if not available in JFR metadata
    Instant now = Instant.now();
    Instant start = now.minusSeconds(60);

    for (Path input : inputPaths) {
      System.out.println("  Adding: " + input);
      converter.addFile(input, start, now);
    }

    byte[] result = converter.convert(outputKind);
    Files.write(outputPath, result);

    long elapsed = System.currentTimeMillis() - startTime;

    System.out.println("Conversion complete!");
    System.out.println("  Output: " + outputPath);
    System.out.println("  Format: " + outputKind);
    System.out.println("  Size: " + formatBytes(result.length));
    System.out.println("  Time: " + elapsed + " ms");

    if (includePayload) {
      long totalInputSize = 0;
      for (Path input : inputPaths) {
        totalInputSize += Files.size(input);
      }
      System.out.println("  Input size: " + formatBytes(totalInputSize));
      System.out.println(
          "  Compression: " + String.format("%.1f%%", 100.0 * result.length / totalInputSize));
    }
  }

  private static String formatBytes(long bytes) {
    if (bytes < 1024) {
      return bytes + " B";
    } else if (bytes < 1024 * 1024) {
      return String.format("%.1f KB", bytes / 1024.0);
    } else {
      return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
  }

  private static void printUsage() {
    System.out.println("JFR to OTLP Converter");
    System.out.println();
    System.out.println("Usage: JfrToOtlpConverterCLI [options] input.jfr [input2.jfr ...] output");
    System.out.println();
    System.out.println("Options:");
    System.out.println("  --json              Output JSON format instead of protobuf");
    System.out.println("  --pretty            Pretty-print JSON output (use with --json)");
    System.out.println(
        "  --include-payload   Include original JFR payload in output (increases size)");
    System.out.println("  --help              Show this help message");
    System.out.println();
    System.out.println("Examples:");
    System.out.println("  # Convert to protobuf (default)");
    System.out.println("  JfrToOtlpConverterCLI recording.jfr output.pb");
    System.out.println();
    System.out.println("  # Convert to compact JSON");
    System.out.println("  JfrToOtlpConverterCLI --json recording.jfr output.json");
    System.out.println();
    System.out.println("  # Convert to pretty-printed JSON");
    System.out.println("  JfrToOtlpConverterCLI --json --pretty recording.jfr output.json");
    System.out.println();
    System.out.println("  # Merge multiple recordings");
    System.out.println("  JfrToOtlpConverterCLI file1.jfr file2.jfr file3.jfr merged.pb");
    System.out.println();
    System.out.println("  # Include original payload");
    System.out.println("  JfrToOtlpConverterCLI --include-payload recording.jfr output.pb");
  }
}
