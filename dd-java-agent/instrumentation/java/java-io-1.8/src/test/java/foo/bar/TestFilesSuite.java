package foo.bar;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

public class TestFilesSuite {

  // ===================== WRITE =====================

  public static OutputStream newOutputStream(final Path path, final OpenOption... options)
      throws IOException {
    return Files.newOutputStream(path, options);
  }

  public static long copyFromStream(
      final InputStream in, final Path target, final CopyOption... options) throws IOException {
    return Files.copy(in, target, options);
  }

  public static Path write(final Path path, final byte[] bytes, final OpenOption... options)
      throws IOException {
    return Files.write(path, bytes, options);
  }

  public static Path writeLines(
      final Path path,
      final Iterable<? extends CharSequence> lines,
      final Charset cs,
      final OpenOption... options)
      throws IOException {
    return Files.write(path, lines, cs, options);
  }

  public static Path writeLinesDefaultCharset(
      final Path path, final Iterable<? extends CharSequence> lines, final OpenOption... options)
      throws IOException {
    return Files.write(path, lines, options);
  }

  public static BufferedWriter newBufferedWriter(
      final Path path, final Charset cs, final OpenOption... options) throws IOException {
    return Files.newBufferedWriter(path, cs, options);
  }

  public static BufferedWriter newBufferedWriterDefaultCharset(
      final Path path, final OpenOption... options) throws IOException {
    return Files.newBufferedWriter(path, options);
  }

  public static Path move(final Path source, final Path target, final CopyOption... options)
      throws IOException {
    return Files.move(source, target);
  }

  // ===================== READ =====================

  public static InputStream newInputStream(final Path path, final OpenOption... options)
      throws IOException {
    return Files.newInputStream(path, options);
  }

  public static byte[] readAllBytes(final Path path) throws IOException {
    return Files.readAllBytes(path);
  }

  public static List<String> readAllLines(final Path path, final Charset cs) throws IOException {
    return Files.readAllLines(path, cs);
  }

  public static List<String> readAllLinesDefaultCharset(final Path path) throws IOException {
    return Files.readAllLines(path);
  }

  public static BufferedReader newBufferedReader(final Path path, final Charset cs)
      throws IOException {
    return Files.newBufferedReader(path, cs);
  }

  public static BufferedReader newBufferedReaderDefaultCharset(final Path path) throws IOException {
    return Files.newBufferedReader(path);
  }

  public static Stream<String> lines(final Path path, final Charset cs) throws IOException {
    return Files.lines(path, cs);
  }

  public static Stream<String> linesDefaultCharset(final Path path) throws IOException {
    return Files.lines(path);
  }
}
