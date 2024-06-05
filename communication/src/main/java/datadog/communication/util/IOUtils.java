package datadog.communication.util;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

public abstract class IOUtils {

  private static final int DEFAULT_BUFFER_SIZE = 4096;

  private IOUtils() {}

  public static @NonNull String readFully(InputStream input) throws IOException {
    return readFully(input, Charset.defaultCharset());
  }

  public static @NonNull String readFully(InputStream input, Charset charset) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    readFully(input, output);
    return new String(output.toByteArray(), charset);
  }

  public static void readFully(InputStream input, OutputStream output) throws IOException {
    byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
    int count;
    while ((count = input.read(buffer)) != -1) {
      output.write(buffer, 0, count);
    }
  }

  public static @NonNull List<String> readLines(final InputStream input) throws IOException {
    return readLines(input, Charset.defaultCharset());
  }

  public static @NonNull List<String> readLines(final InputStream input, final Charset charset)
      throws IOException {
    final InputStreamReader reader = new InputStreamReader(input, charset);
    return readLines(reader);
  }

  public static @NonNull List<String> readLines(final Reader input) throws IOException {
    final BufferedReader reader = new BufferedReader(input, DEFAULT_BUFFER_SIZE);
    final List<String> list = new ArrayList<>();
    String line = reader.readLine();
    while (line != null) {
      list.add(line);
      line = reader.readLine();
    }
    return list;
  }

  public static void copyFolder(Path src, Path dest) throws IOException {
    Files.walkFileTree(
        src,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
              throws IOException {
            Files.createDirectories(dest.resolve(src.relativize(dir)));
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            Files.copy(file, dest.resolve(src.relativize(file)));
            return FileVisitResult.CONTINUE;
          }
        });
  }
}
