package datadog.trace.instrumentation.springweb6.boot;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.View;

/**
 * Registers views as beans so the view names returned by {@link RenderTestController} are resolved
 * through the view resolver chain (Boot registers a {@code BeanNameViewResolver} when a {@link
 * View} bean exists).
 */
@Configuration
public class RenderTestConfig {
  public static final String TEXT_VIEW = "textView";
  public static final String FAILING_VIEW = "failingView";
  public static final String TEXT_VIEW_BODY = "rendered";
  public static final String FAILING_VIEW_MESSAGE = "view rendering failed";

  @Bean(TEXT_VIEW)
  public View textView() {
    return new TextView();
  }

  @Bean(FAILING_VIEW)
  public View failingView() {
    return new FailingView();
  }

  static final class TextView implements View {
    @Override
    public String getContentType() {
      return "text/plain";
    }

    @Override
    public void render(
        final Map<String, ?> model,
        final HttpServletRequest request,
        final HttpServletResponse response)
        throws IOException {
      response.setContentType(getContentType());
      response.getWriter().write(TEXT_VIEW_BODY);
    }
  }

  static final class FailingView implements View {
    @Override
    public String getContentType() {
      return "text/plain";
    }

    @Override
    public void render(
        final Map<String, ?> model,
        final HttpServletRequest request,
        final HttpServletResponse response) {
      throw new IllegalStateException(FAILING_VIEW_MESSAGE);
    }
  }
}
