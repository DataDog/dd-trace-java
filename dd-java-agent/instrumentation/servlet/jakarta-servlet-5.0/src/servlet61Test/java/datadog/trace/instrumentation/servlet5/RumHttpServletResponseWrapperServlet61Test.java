package datadog.trace.instrumentation.servlet5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.trace.bootstrap.instrumentation.buffer.InjectingPipeWriter;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.StringWriter;
import java.lang.reflect.Field;
import org.tabletest.junit.TableTest;

class RumHttpServletResponseWrapperServlet61Test {

  @TableTest({
    "scenario      | clearBuffer",
    "clear buffer  | true       ",
    "retain buffer | false      "
  })
  void servlet61RedirectRespectsClearBuffer(boolean clearBuffer)
      throws IOException, ReflectiveOperationException {
    ServletContext servletContext = mock(ServletContext.class);
    when(servletContext.getEffectiveMajorVersion()).thenReturn(6);
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getServletContext()).thenReturn(servletContext);
    HttpServletResponse response = mock(HttpServletResponse.class);
    RumHttpServletResponseWrapper wrapper = new RumHttpServletResponseWrapper(request, response);

    StringWriter downstream = new StringWriter();
    InjectingPipeWriter pipe =
        new InjectingPipeWriter(
            downstream, "</head>".toCharArray(), "<script></script>".toCharArray());
    setWrappedPipeWriter(wrapper, pipe);
    pipe.write("</he");

    wrapper.sendRedirect("/other", 307, clearBuffer);
    wrapper.commit();

    assertEquals(clearBuffer ? "" : "</he", downstream.toString());
    verify(response).sendRedirect("/other", 307, clearBuffer);
  }

  private static void setWrappedPipeWriter(
      RumHttpServletResponseWrapper wrapper, InjectingPipeWriter pipe)
      throws ReflectiveOperationException {
    Field field = RumHttpServletResponseWrapper.class.getDeclaredField("wrappedPipeWriter");
    field.setAccessible(true);
    field.set(wrapper, pipe);
  }
}
