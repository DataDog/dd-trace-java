package datadog.trace.api.civisibility.domain;

import javax.annotation.Nullable;

public enum Language {
  JAVA(".java", false),
  GROOVY(".groovy", false),
  KOTLIN(".kt", false),
  SCALA(".scala", false),
  GHERKIN(".feature", true);

  private static final Language[] UNIVERSE = Language.values();

  private final String extension;
  private final boolean nonCode;

  Language(String extension, boolean nonCode) {
    this.extension = extension;
    this.nonCode = nonCode;
  }

  public String getExtension() {
    return extension;
  }

  public boolean isNonCode() {
    return nonCode;
  }

  @Nullable
  public static Language getByFileName(String fileName) {
    for (Language language : UNIVERSE) {
      if (fileName.endsWith(language.extension)) {
        return language;
      }
    }
    return null;
  }

  public static Language getByOrdinal(int ordinal) {
    return UNIVERSE[ordinal];
  }
}
