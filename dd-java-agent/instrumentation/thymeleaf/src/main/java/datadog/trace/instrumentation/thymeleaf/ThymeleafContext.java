package datadog.trace.instrumentation.thymeleaf;

public class ThymeleafContext {

  private String templateName;

  private int line;

  public ThymeleafContext(String file, int line) {
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
