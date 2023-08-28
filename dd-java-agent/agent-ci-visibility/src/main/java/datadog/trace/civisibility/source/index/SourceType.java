package datadog.trace.civisibility.source.index;

enum SourceType {
  JAVA(".java"),
  GROOVY(".groovy"),
  KOTLIN(".kt"),
  SCALA(".scala");

  private static final SourceType[] UNIVERSE = SourceType.values();

  private final String extension;

  SourceType(String extension) {
    this.extension = extension;
  }

  public String getExtension() {
    return extension;
  }

  static SourceType getByFileName(String fileName) {
    for (SourceType sourceType : UNIVERSE) {
      if (fileName.endsWith(sourceType.extension)) {
        return sourceType;
      }
    }
    return null;
  }
}
