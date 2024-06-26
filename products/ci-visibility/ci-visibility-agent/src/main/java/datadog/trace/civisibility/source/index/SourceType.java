package datadog.trace.civisibility.source.index;

enum SourceType {
  JAVA(".java", false),
  GROOVY(".groovy", false),
  KOTLIN(".kt", false),
  SCALA(".scala", false),
  GHERKIN(".feature", true);

  private static final SourceType[] UNIVERSE = SourceType.values();

  private final String extension;
  private final boolean resource;

  SourceType(String extension, boolean resource) {
    this.extension = extension;
    this.resource = resource;
  }

  public String getExtension() {
    return extension;
  }

  public boolean isResource() {
    return resource;
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
