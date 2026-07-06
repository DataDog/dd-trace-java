package datadog.smoketest.springboot.controller;

import static java.util.Arrays.asList;

import datadog.smoketest.springboot.entity.Author;
import datadog.smoketest.springboot.entity.Book;
import datadog.smoketest.springboot.entity.Library;
import datadog.smoketest.springboot.entity.Owner;
import datadog.smoketest.springboot.service.LibraryService;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.hibernate.collection.internal.PersistentBag;
import org.hibernate.proxy.HibernateProxy;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.SessionAttributes;
import org.springframework.web.bind.support.SessionStatus;

@Controller
@RequestMapping("/library")
@SessionAttributes(LibraryController.COMMAND_NAME)
@RequiredArgsConstructor
public class LibraryController {

  public static final String COMMAND_NAME = "library";

  private final LibraryService libraryService;

  @GetMapping("/{id}")
  public ResponseEntity<Boolean> hasIssue(@PathVariable final int id) {
    return ResponseEntity.ok(libraryService.findLibraryById(id).isIssueExists());
  }

  @GetMapping
  public ResponseEntity<Integer> create() {
    final Library library =
        Library.builder()
            .books(
                asList(
                    Book.builder()
                        .title("The Lord of the Rings")
                        .owner(Owner.builder().name("Peter Jackson").build())
                        .authors(
                            asList(
                                Author.builder().name("J.R.R Tolkien").build(),
                                Author.builder().name("Peter Jackson").build()))
                        .build(),
                    Book.builder()
                        .title("The Hobbit")
                        .owner(Owner.builder().name("Edith Tolkien").build())
                        .authors(
                            asList(
                                Author.builder().name("J.R.R Tolkien").build(),
                                Author.builder().name("Edith Tolkien").build()))
                        .build()))
            .build();
    libraryService.save(library);
    return ResponseEntity.ok(library.getId());
  }

  @GetMapping("/update/{id}")
  public String update(@PathVariable final int id, final ModelMap model) {
    final Library library = libraryService.findLibraryById(id);
    model.put(COMMAND_NAME, library);
    return "update";
  }

  @GetMapping("/update")
  public String update(
      @ModelAttribute(COMMAND_NAME) final Library library, final SessionStatus sessionStatus) {
    libraryService.update(library);
    sessionStatus.setComplete();
    return "redirect:/library/" + library.getId();
  }

  @GetMapping("/session/add/{id}")
  public ResponseEntity<String> addToSession(
      @RequestParam("mode") final String mode,
      @PathVariable final int id,
      final HttpServletRequest request) {
    final Library library = libraryService.findLibraryById(id);
    final Book book = library.getBooks().get(0);
    final HttpSession session = request.getSession();
    if (mode.equals("one-to-one")) {
      session.setAttribute(mode, book.getOwner());
    } else {
      session.setAttribute(mode, book.getAuthors());
    }
    return ResponseEntity.ok("OK");
  }

  @GetMapping("/session/validate")
  public ResponseEntity<Boolean> validateSession(
      @RequestParam("mode") final String mode, final HttpServletRequest request) {
    final HttpSession session = request.getSession();
    final Object sessionItem = session.getAttribute(mode);
    final boolean loaded;
    if (sessionItem instanceof Owner) {
      final HibernateProxy proxy = (HibernateProxy) sessionItem;
      loaded = !proxy.getHibernateLazyInitializer().isUninitialized();
    } else {
      final PersistentBag bag = (PersistentBag) sessionItem;
      loaded = bag.wasInitialized();
    }
    return ResponseEntity.ok(loaded);
  }
}
