package datadog.trace.instrumentation.thymeleaf;

public class ThymeleafContext {

  private final String templateName;

  private final int line;

  public ThymeleafContext(final String file, final int line) {
    this.templateName = file;
    this.line = line;
  }

  public String getTemplateName() {
    return templateName;
  }

  public int getLine() {
    return line;
  }
}
