package datadog.trace.instrumentation.servlet5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
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
    "scenario      | clearBuffer | rejected",
    "clear buffer  | true        | false   ",
    "retain buffer | false       | false   ",
    "rejected call | true        | true    "
  })
  void servlet61RedirectRespectsClearBuffer(boolean clearBuffer, boolean rejected)
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

    if (rejected) {
      doThrow(new IllegalStateException("response already committed"))
          .when(response)
          .sendRedirect("/other", 307, clearBuffer);
      assertThrows(
          IllegalStateException.class, () -> wrapper.sendRedirect("/other", 307, clearBuffer));
    } else {
      wrapper.sendRedirect("/other", 307, clearBuffer);
    }
    wrapper.commit();

    assertEquals(clearBuffer && !rejected ? "" : "</he", downstream.toString());
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
