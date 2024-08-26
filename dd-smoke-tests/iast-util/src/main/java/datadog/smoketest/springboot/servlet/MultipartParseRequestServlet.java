package datadog.smoketest.springboot.servlet;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.util.List;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.FileItemFactory;
import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;

public class MultipartParseRequestServlet extends HttpServlet {

  @Override
  public void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
    final FileItemFactory fileItemFactory = new DiskFileItemFactory();
    final ServletFileUpload servletFileUpload = new ServletFileUpload(fileItemFactory);
    try {
      final List<FileItem> fileItemIterator = servletFileUpload.parseRequest(request);
      final ObjectInputStream ois = new ObjectInputStream(fileItemIterator.get(0).getInputStream());
      ois.close();
      response.setHeader("Content-Type", "text/plain");
      response.getWriter().write("OK");
    } catch (FileUploadException e) {
      throw new IOException(e);
    }
  }
}
