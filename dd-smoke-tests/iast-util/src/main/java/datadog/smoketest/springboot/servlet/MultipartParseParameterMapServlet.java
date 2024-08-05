package datadog.smoketest.springboot.servlet;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.util.List;
import java.util.Map;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.FileItemFactory;
import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;

public class MultipartParseParameterMapServlet extends HttpServlet {

  @Override
  public void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
    final FileItemFactory fileItemFactory = new DiskFileItemFactory();
    final ServletFileUpload servletFileUpload = new ServletFileUpload(fileItemFactory);
    try {
      final Map<String, List<FileItem>> fileItemList = servletFileUpload.parseParameterMap(request);
      for (final List<FileItem> fileItem : fileItemList.values()) {
        final ObjectInputStream ois = new ObjectInputStream(fileItem.get(0).getInputStream());
        ois.close();
      }
      response.setHeader("Content-Type", "text/plain");
      response.getWriter().write("OK");
    } catch (FileUploadException e) {
      throw new IOException(e);
    }
  }
}
