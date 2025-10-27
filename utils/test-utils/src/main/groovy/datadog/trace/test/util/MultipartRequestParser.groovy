package datadog.trace.test.util

import org.apache.commons.fileupload.FileItem
import org.apache.commons.fileupload.FileItemFactory
import org.apache.commons.fileupload.FileItemHeaders
import org.apache.commons.fileupload.FileUpload
import org.apache.commons.fileupload.FileUploadException
import org.apache.commons.fileupload.ParameterParser
import org.apache.commons.fileupload.UploadContext

import java.nio.charset.StandardCharsets

class MultipartRequestParser {

  static Map<String, List<FileItem>> parseRequest(byte[] requestBody, String contentTypeHeader) throws FileUploadException {
    FileUpload fileUpload = new FileUpload(new MemoryFileItemFactory())
    return fileUpload.parseParameterMap(new SimpleContext(requestBody, contentTypeHeader))
  }

  private static class SimpleContext implements UploadContext {
    private final byte[] request
    private final String contentType

    private SimpleContext(byte[] requestBody, String contentTypeHeader) {
      this.request = requestBody
      this.contentType = contentTypeHeader
    }

    @Override
    long contentLength() {
      return request.length
    }

    @Override
    String getCharacterEncoding() {
      ParameterParser parser = new ParameterParser()
      parser.setLowerCaseNames(true)
      String charset = parser.parse(contentType, (char)';').get("charset")
      return charset != null ? charset : "UTF-8"
    }

    @Override
    int getContentLength() {
      return request.length
    }

    @Override
    String getContentType() {
      return contentType
    }

    @Override
    InputStream getInputStream() throws IOException {
      return new ByteArrayInputStream(request)
    }
  }

  private static class MemoryFileItem implements FileItem {
    private String fieldName
    private String fileName
    private String contentType
    private boolean isFormField
    private FileItemHeaders headers
    private final ByteArrayOutputStream os = new ByteArrayOutputStream()

    MemoryFileItem(String fieldName, String contentType, boolean isFormField, String fileName) {
      this.fieldName = fieldName
      this.contentType = contentType
      this.isFormField = isFormField
      this.fileName = fileName
    }

    @Override
    void delete() {
    }

    @Override
    byte[] get() {
      return os.toByteArray()
    }

    @Override
    String getContentType() {
      return contentType
    }

    @Override
    String getFieldName() {
      return fieldName
    }

    @Override
    InputStream getInputStream() throws IOException {
      return new ByteArrayInputStream(get())
    }

    @Override
    String getName() {
      return fileName
    }

    @Override
    OutputStream getOutputStream() throws IOException {
      return os
    }

    @Override
    long getSize() {
      return os.size()
    }

    @Override
    String getString() {
      return new String(get(), StandardCharsets.UTF_8)
    }

    @Override
    String getString(String encoding) throws UnsupportedEncodingException {
      return new String(get(), encoding)
    }

    @Override
    boolean isFormField() {
      return isFormField
    }

    @Override
    boolean isInMemory() {
      return true
    }

    @Override
    void setFieldName(String name) {
      fieldName = name
    }

    @Override
    void setFormField(boolean state) {
      isFormField = state
    }

    @Override
    void write(File file) throws Exception {
    }

    @Override
    FileItemHeaders getHeaders() {
      return headers
    }

    @Override
    void setHeaders(FileItemHeaders headers) {
      this.headers = headers
    }
  }

  private static class MemoryFileItemFactory implements FileItemFactory {
    @Override
    FileItem createItem(String fieldName, String contentType, boolean isFormField, String fileName) {
      return new MemoryFileItem(fieldName, contentType, isFormField, fileName)
    }
  }
}
