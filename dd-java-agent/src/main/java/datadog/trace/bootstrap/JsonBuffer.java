import java.io.Flushable;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.Charset;

/**
 * Light weight JSON writer with no dependencies other than JDK
 * Modeled after GSON JsonWriter
 */
public final class JsonBuffer implements Flushable {
  private final PrintWriter writer;
  
  private boolean lastWasValue = false;
  
  public JsonBuffer(OutputStream out, Charset charset) {
    this(new PrintWriter(new OutputStreamWriter(out, charset), false));
  }
  
  public JsonBuffer(PrintStream stream) {
    this.writer = new PrintWriter(stream);
  }
  
  public JsonBuffer(PrintWriter writer) {
    this.writer = writer;
  }
  
  public JsonBuffer beginObject() {
    injectCommaIfNeeded();
    
    return write('{');
  }
  
  public JsonBuffer endObject() {
    
    return write('}');
  }
  
  public JsonBuffer name(String key) {
    injectCommaIfNeeded();
    
    return writeStringLiteral(key).write(':');
  }
  
  public JsonBuffer nullValue() {
    injectCommaIfNeeded();
    endsValue();
    
    return this.writeStringRaw("null");
  }
  
  public JsonBuffer value(boolean value) {
    injectCommaIfNeeded();
    endsValue();
     
    return writeStringRaw(value ? "true" : "false");
  }
  
  public JsonBuffer value(String value) {
    injectCommaIfNeeded();
    endsValue();
    
    return writeStringLiteral(value);
  }
  
  public JsonBuffer value(int value) {
    injectCommaIfNeeded();
    endsValue();
    
    return writeStringRaw(Integer.toString(value));
  }
  
  public JsonBuffer beginArray() {
    injectCommaIfNeeded();
    
    return write('[');
  }
  
  public JsonBuffer endArray() {
    endsValue();
    
    return write(']');
  }
  
  public void flush() {
    writer.flush();
  }
  
  void injectCommaIfNeeded() {
    if ( lastWasValue ) this.write(',');
    lastWasValue = false;
  }
  
  void endsValue() {
    lastWasValue = true;
  }
  
  private JsonBuffer write(char ch) {
    writer.write(ch);
    return this;
  }
  
  private JsonBuffer writeStringLiteral(String str) {
    writer.write('"');

    // DQH - indexOf is usually intrinsifed to use SIMD & 
    // no escaping will be the common case
    if ( str.indexOf('"') == -1 ) {
      writer.write(str);
    } else {
      for ( int i = 0; i < str.length(); ++i ) {
        char ch = str.charAt(i);
        
        switch ( ch ) {
          case '"':
          writer.write("\\\"");
          break;
          
          default:
          writer.write(ch);
          break;
        }
      }
    }
    writer.write('"');
    
    return this;
  }
  
  private JsonBuffer writeStringRaw(String str) {
    writer.write(str);
    return this;
  }
}
