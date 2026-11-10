package datadog.trace.instrumentation.springweb6.boot;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.Properties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.View;
import org.springframework.web.servlet.handler.SimpleMappingExceptionResolver;

/**
 * Registers views as beans so the view names returned by {@link RenderTestController} are resolved
 * through the view resolver chain (Boot registers a {@code BeanNameViewResolver} when a {@link
 * View} bean exists), and a {@link SimpleMappingExceptionResolver} that resolves {@link
 * RenderTestController.ErrorViewException} to {@link #ERROR_VIEW}.
 */
@Configuration
public class RenderTestConfig {
  public static final String TEXT_VIEW = "textView";
  public static final String FAILING_VIEW = "failingView";
  public static final String TEXT_VIEW_BODY = "rendered";
  public static final String FAILING_VIEW_MESSAGE = "view rendering failed";
  public static final String ERROR_VIEW = "errorView";
  public static final String ERROR_VIEW_BODY = "error view";
  public static final int ERROR_VIEW_STATUS = 503;

  @Bean(TEXT_VIEW)
  public View textView() {
    return new TextView();
  }

  @Bean(FAILING_VIEW)
  public View failingView() {
    return new FailingView();
  }

  @Bean(ERROR_VIEW)
  public View errorView() {
    return new TextView(ERROR_VIEW_BODY);
  }

  @Bean
  public SimpleMappingExceptionResolver errorViewExceptionResolver() {
    // maps only ErrorViewException, so other exceptions keep their error handling
    final SimpleMappingExceptionResolver resolver = new SimpleMappingExceptionResolver();
    final Properties mappings = new Properties();
    mappings.setProperty(RenderTestController.ErrorViewException.class.getName(), ERROR_VIEW);
    resolver.setExceptionMappings(mappings);
    resolver.addStatusCode(ERROR_VIEW, ERROR_VIEW_STATUS);
    return resolver;
  }

  static final class TextView implements View {
    private final String body;

    TextView() {
      this(TEXT_VIEW_BODY);
    }

    TextView(final String body) {
      this.body = body;
    }

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
      response.getWriter().write(body);
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
