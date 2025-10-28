package datadog.communication.http

import okhttp3.Request
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

class SafeRequestBuilderTest {
  Request.Builder testBuilder = new Request.Builder()

  @Test
  void "test adding bad header"() {
    def name = 'bad'
    def password = 'very-secret-password'
    IllegalArgumentException ex = assertThrows(IllegalArgumentException, {
      testBuilder.url("http:localhost").addHeader(name, "$password\n")
    })
    assertTrue(ex.getMessage().contains(name))
    assertFalse(ex.getMessage().contains(password))
  }

  @Test
  void "test adding bad header2"(){
    def name = '\u0019'
    def password = 'very-secret-password'
    IllegalArgumentException ex = assertThrows(IllegalArgumentException, {
      testBuilder.url("http:localhost").addHeader(name, "\u0080$password")
    })
    assertTrue(ex.getMessage().contains(name))
    assertFalse(ex.getMessage().contains(password))
  }
}
