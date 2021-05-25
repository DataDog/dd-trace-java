package io.sqreen.testapp.sampleapp

import de.thetaphi.forbiddenapis.SuppressForbidden
import io.sqreen.testapp.imitation.VulnerableExecutions
import io.sqreen.testapp.imitation.VulnerableFiles
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.http.MediaType
import org.springframework.security.util.InMemoryResource
import org.springframework.stereotype.Controller
import org.springframework.util.StreamUtils
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import org.xml.sax.InputSource

import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.Charset
import java.util.concurrent.Callable

import static java.lang.Thread.currentThread

@Controller
@SuppressForbidden
class MoreVulnerabilitiesController {

  private static final Charset ISO_8859_1_CS = Charset.forName("ISO-8859-1")

  @RequestMapping(value = '/lfi', produces = 'text/plain')
  @ResponseBody
  Resource showFile(String q, @RequestParam(required = false) String strategy) {
    if (strategy == 'file_channel') {
      FileChannel ch = Class.forName('java.nio.file.Files').newByteChannel(
        Class.forName('java.nio.file.FileSystems').getDefault().getPath(q))
      ByteBuffer buf = ByteBuffer.allocate(128)
      StringBuffer sb = new StringBuffer()
      while (ch.read(buf) > 0) {
        buf.rewind()
        sb.append(ISO_8859_1_CS.decode(buf))
        buf.flip()
      }
      new InMemoryResource(sb.toString())
    } else if (strategy == 'random_access_file') {
      RandomAccessFile rac = new RandomAccessFile(q, 'r')
      StringBuffer sb = new StringBuffer()
      byte[] b = new byte[128]
      int read
      while ((read = rac.read(b)) > 0) {
        sb.append(new String(b, 0, read, ISO_8859_1_CS))
      }
      new InMemoryResource(sb.toString())
    } else {
      new FileSystemResource(VulnerableFiles.getFile(q))
    }
  }

  @RequestMapping(value = '/ping', params = ['q'])
  StreamingResponseBody ping(String q) {
    InputStream is = VulnerableExecutions.ping(q)
    return { OutputStream out ->
      StreamUtils.copy(is, out)
    } as StreamingResponseBody
  }

  @RequestMapping(value = '/asyncPing', params = ['q'])
  Callable<StreamingResponseBody> asyncPing(String q, HttpServletResponse resp) { {

      ->
      resp.addHeader('X-Sqreen-Thread', currentThread().name)
      ping(q)
    }
  }

  @RequestMapping(value = '/ping', params = ['ip'])
  StreamingResponseBody pingNoShell(String ip) {
    InputStream is = VulnerableExecutions.pingNoShell(ip)
    return { OutputStream out ->
      StreamUtils.copy(is, out)
    } as StreamingResponseBody
  }

  @RequestMapping('/eval')
  @ResponseBody
  String eval(String q, @RequestParam(required = false) String strategy) {
    return VulnerableExecutions.eval(q, strategy, this)
  }


  @RequestMapping('/xss')
  ModelAndView xss(String q, @RequestParam(required = false, defaultValue = '') String suffix) {
    new ModelAndView("xss$suffix", [q: q])
  }

  @RequestMapping('/shellshock/')
  StreamingResponseBody shellshock(@RequestParam(required = false) String q,
    @RequestHeader(name = "Referer", required = false) String referer) {
    String valueToUse = q ?: referer
    InputStream is = VulnerableExecutions.shellShock(valueToUse)
    return { OutputStream out ->
      StreamUtils.copy(is, out)
    } as StreamingResponseBody
  }


  /* Call with
   * curl -H 'Content-type: text/xml' -H 'Accept: application/json' \
   *   http://localhost:8080/xxe_form?variant=stax_jdk -d '@-' \
   *   <(curl http://artefacto-test.s3.amazonaws.com/file.xml)
   *
   * See io.sqreen.testapp.sample_app.XXEVulnerabilitiesTests for the contents
   * of these files.
   */
  @PostMapping(value = '/xxe_form', produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  XXEVulnerabilities.NameAndEmail xxeForm(HttpServletRequest req,
    @RequestParam(defaultValue = 'dom_xerces') String variant,
    @RequestParam(defaultValue = 'true') boolean vulnerable,
    @RequestParam(defaultValue = 'false') boolean customResolver
  ) {
    def vul = new XXEVulnerabilities(vulnerable: vulnerable)
    if (customResolver) {
      vul.customResolver = { String publicId, String systemId ->
        if (systemId.endsWith('sample.dtd')) {
          InputStream stream = getClass().classLoader.getResourceAsStream('sample.dtd')
          return new InputSource(stream)
        } else if (systemId.endsWith('sample.txt')) {
          InputStream stream = getClass().classLoader.getResourceAsStream('sample.txt')
          return new InputSource(stream)
        }
        new InputSource(new StringReader(''))
      }
    }

    vul.parseWithVariant(variant, req.inputStream)
  }

  @PostMapping(value = '/xxe_form_spring', produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  XXEVulnerabilities.NameAndEmail xxeForm(@RequestBody XXEVulnerabilities.NameAndEmail nameAndEmail) {
    nameAndEmail
  }

  // Compliance endpoint: main one: /eval
  @PostMapping(value = '/exec', consumes = "application/json")
  StreamingResponseBody exec(@RequestBody Map map) {
    InputStream is = VulnerableExecutions.exec(map.command)
    return { OutputStream out ->
      StreamUtils.copy(is, out)
    } as StreamingResponseBody
  }

  // Compliance endpoint: main one: /eval
  // https://github.com/spring-projects/spring-framework/issues/22734
  @PostMapping(value = '/exec', consumes = "application/x-www-form-urlencoded")
  StreamingResponseBody execUrlEncoded(@RequestParam('command') cmd) {
    exec command: cmd
  }
}
