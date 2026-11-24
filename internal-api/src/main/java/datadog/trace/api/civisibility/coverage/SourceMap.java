package datadog.trace.api.civisibility.coverage;

/**
 * The source files and lines that the line numbers of a class stand for, as stated by the class's
 * JSR-45 source map (SMAP). The Kotlin compiler writes one for every class with inlined code: the
 * inlined lines get numbers past the end of the file, and the map says which file and line each one
 * came from.
 */
public final class SourceMap {

  private static final int RANGE_SIZE = 5;

  private final String[] files;

  /**
   * Line ranges in the order of the map, {@value #RANGE_SIZE} ints each: the first and the last
   * line number of the class, the first source line, the number of class lines per source line, and
   * the file index (-1 when the map names no such file).
   */
  private final int[] ranges;

  /**
   * @param files source files as their package directory followed by their name, such as {@code
   *     com/example/Util.kt}
   * @param ranges see {@link #ranges}
   */
  public SourceMap(String[] files, int[] ranges) {
    this.files = files;
    this.ranges = ranges;
  }

  public int fileCount() {
    return files.length;
  }

  /** The source file of a file index, as its package directory followed by its name. */
  public String file(int fileIndex) {
    return files[fileIndex];
  }

  /**
   * Maps a line number of the class through the first range that holds it.
   *
   * @return the file index in the high 32 bits and the source line in the low 32 bits, or -1 if the
   *     map does not place the line in a file
   */
  public long map(int line) {
    for (int i = 0; i < ranges.length; i += RANGE_SIZE) {
      if (line >= ranges[i] && line <= ranges[i + 1]) {
        int file = ranges[i + 4];
        int sourceLine = ranges[i + 2] + (line - ranges[i]) / ranges[i + 3];
        return file >= 0 && sourceLine > 0 ? ((long) file << 32) | sourceLine : -1;
      }
    }
    return -1;
  }
}
