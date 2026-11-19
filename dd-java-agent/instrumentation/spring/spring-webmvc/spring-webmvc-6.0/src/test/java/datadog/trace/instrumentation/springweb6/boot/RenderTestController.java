package datadog.trace.instrumentation.springweb6.boot;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.ModelAndView;

/**
 * Returns views that go through the view resolver chain: names of the beans registered by {@link
 * RenderTestConfig}, and {@code forward:} / {@code redirect:} view names.
 */
@Controller
public class RenderTestController {
  public static final String REDIRECT_VIEW_NAME = "redirect:/success";
  public static final String ERROR_VIEW_MESSAGE = "resolved to an error view";

  @GetMapping("/render/named")
  public String named() {
    return RenderTestConfig.TEXT_VIEW;
  }

  @GetMapping("/render/failing")
  public String failing() {
    return RenderTestConfig.FAILING_VIEW;
  }

  @GetMapping("/render/forward")
  public String forward() {
    return "forward:" + ForwardTargetController.PATH;
  }

  @GetMapping("/render/error-view")
  public String errorView() {
    throw new ErrorViewException(ERROR_VIEW_MESSAGE);
  }

  @GetMapping("/render/redirect-view-name")
  public String redirectViewName() {
    return REDIRECT_VIEW_NAME;
  }

  @GetMapping("/render/redirect-model-and-view")
  public ModelAndView redirectModelAndView() {
    return new ModelAndView(REDIRECT_VIEW_NAME);
  }

  /** Mapped to {@link RenderTestConfig#ERROR_VIEW} by the config's exception resolver. */
  public static class ErrorViewException extends RuntimeException {
    public ErrorViewException(final String message) {
      super(message);
    }
  }
}
