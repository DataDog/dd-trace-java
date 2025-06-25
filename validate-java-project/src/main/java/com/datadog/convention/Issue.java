package com.datadog.convention;

import java.nio.file.Path;

public record Issue(String file, String comment, boolean error) {


  public static Issue error(Path path, String comment) {
    return error(path.toString(), comment);
  }

  public static Issue error(String file, String comment) {
    return new Issue(file, comment, true);
  }

  public static Issue warning(Path path, String comment) {
    return warning(path.toString(), comment);
  }

  public static Issue warning(String file, String comment) {
    return new Issue(file, comment, false);
  }

  @Override
  public String toString() {
    return (this.error ? "[error]" : "[warning]") + " " + this.file + ": " + this.comment;
  }
}
