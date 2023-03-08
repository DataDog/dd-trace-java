package datadog.telemetry.api;

public class ProductError {

  @com.squareup.moshi.Json(name = "code")
  private Integer code;

  @com.squareup.moshi.Json(name = "message")
  private String message;

  /**
   * Get code
   *
   * @return code
   */
  public Integer getCode() {
    return code;
  }

  /** Set code */
  public void setCode(Integer code) {
    this.code = code;
  }

  public ProductError code(Integer code) {
    this.code = code;
    return this;
  }

  /**
   * Get message
   *
   * @return message
   */
  public String getMessage() {
    return message;
  }

  /** Set message */
  public void setMessage(String message) {
    this.message = message;
  }

  public ProductError message(String message) {
    this.message = message;
    return this;
  }

  /** Create a string representation of this pojo. */
  @Override
  public String toString() {
    final StringBuffer sb = new StringBuffer("ProductError{");
    sb.append("code=").append(code);
    sb.append(", message='").append(message).append('\'');
    sb.append('}');
    return sb.toString();
  }
}
