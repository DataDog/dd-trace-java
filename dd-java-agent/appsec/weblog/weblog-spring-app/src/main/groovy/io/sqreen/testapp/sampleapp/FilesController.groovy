package io.sqreen.testapp.sampleapp

import io.sqreen.testapp.imitation.VulnerableFiles
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.servlet.mvc.support.RedirectAttributes

@RestController
class FilesController {

  @PostMapping(value = "upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  String handleFileUpload(@RequestParam("file") MultipartFile file, RedirectAttributes redirectAttributes) {

    redirectAttributes.addFlashAttribute('message', 'file uploaded')
    return VulnerableFiles.store(file.inputStream)
  }
}
