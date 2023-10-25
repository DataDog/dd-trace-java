package datadog.trace.bootstrap.debugger;

/** Store evaluation errors from expressions (probe conditions, log template, metric values, ...) */
public class EvaluationError {
  private final String expr;
  private final String message;

  public EvaluationError(String expr, String message) {
    this.expr = expr;
    this.message = message;
  }

  public String getExpr() {
    return expr;
  }

  public String getMessage() {
    return message;
  }
}
