package io.sqreen.testapp.sampleapp.posts

import groovy.transform.TypeChecked
import org.hibernate.Query
import org.hibernate.Session
import org.hibernate.SessionFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Controller
import org.springframework.ui.ModelMap
import org.springframework.validation.BindingResult
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.servlet.ModelAndView

import javax.persistence.EntityManager
import javax.persistence.TypedQuery
import javax.transaction.Transactional

import static org.springframework.web.bind.annotation.RequestMethod.*

@Controller
@RequestMapping('/posts')
@TypeChecked
class PostController {

  @Autowired
  private PostRepository postRepository

  // see comment on PostRepository
  @Autowired
  private List<EntityManager> entityManager

  @Autowired
  private SessionFactory sessionFactory

  @RequestMapping(method = GET)
  ModelAndView showAllPosts(Post post) {
    new ModelAndView('posts', [posts: postRepository.all])
  }

  // vulnerable endpoint (SQL injection)
  @RequestMapping(method = GET, path = '/{stringId}')
  ModelAndView showPostById(Post post,
    @PathVariable('stringId') String id,
    @RequestParam(value = 'xss_message', required = false) String xssMessage) {
    new ModelAndView('posts', [posts: [postRepository.find(id)], xssMessage: xssMessage])
  }

  // vulnerable endpoint (SQL injection) — Post variant with text/plain
  @RequestMapping(method = POST, path = '/show/', consumes = "text/plain")
  ModelAndView showPostByIdPostString(Post post,
    @RequestBody String id) {
    new ModelAndView('posts', [posts: [postRepository.find(id)]])
  }

  // vulnerable endpoint (SQL injection) — Post variant with application/json
  @RequestMapping(method = POST, path = '/show/', consumes = "application/json")
  ModelAndView showPostByIdPostJson(Post post,
    @RequestBody Map<Object, Object> map) {
    new ModelAndView('posts', [posts: [postRepository.find(map['id'] as String)]])
  }

  // vulnerable endpoint (SQL injection) — Post variant with multipart/form-data and @RequestParam
  @RequestMapping(method = PUT, path = '/show/', consumes = "multipart/form-data")
  ModelAndView showPostByIdPostMultipart(@RequestParam String id) {
    new ModelAndView('posts', [post: new Post(),
      posts: [postRepository.find(id)]])
  }

  // vulnerable endpoint (SQL injection) — Post variant with multipart/form-data and @RequestPart
  @RequestMapping(method = PUT, path = '/show/RequestPart/', consumes = "multipart/form-data")
  ModelAndView showPostByIdPostMultipartPart(@RequestPart("jsonData") Map data) {
    new ModelAndView('posts', [post: new Post(),
      posts: [postRepository.find(data['id'] as String)]])
  }

  // vulnerable endpoint (EntityManager.createQuery)
  @RequestMapping(method = GET, path = '/jpa/{stringId}')
  ModelAndView showPostByIdJpa(Post post, @PathVariable('stringId') String id) {
    TypedQuery<Post> query = entityManager.first().createQuery("select p from Post p where p.id = $id", Post)

    new ModelAndView('posts', [posts: query.resultList])
  }

  // vulnerable endpoint (EntityManager.createNativeQuery)
  @RequestMapping(method = GET, path = '/jpaNative/{stringId}')
  ModelAndView showPostByIdJpaNative(Post post, @PathVariable('stringId') String id) {
    def existingPost = postRepository.findJpaNative(id)
    new ModelAndView('posts', [posts: [existingPost]])
  }

  // vulnerable endpoint (Session)
  @RequestMapping(method = GET, path = '/hibernate/{stringId}')
  @Transactional
  ModelAndView showPostByIdHibernate(Post post, @PathVariable('stringId') String id) {
    Session session = sessionFactory.currentSession
    Query query = session.createQuery("select p from Post p where p.id = $id")

    new ModelAndView('posts', [posts: query.list()])
  }

  // vulnerable endpoint (Session.createSqlQuery)
  @RequestMapping(method = GET, path = '/hibernateNative/{stringId}')
  ModelAndView showPostByIdHibernateNative(Post post, @PathVariable('stringId') String id) {
    def existingPost = postRepository.findHibernateNative(id)
    new ModelAndView('posts', [posts: existingPost ? [existingPost]: []])
  }

  @RequestMapping(method = POST)
  ModelAndView addPost(Post post, BindingResult bindingResult, ModelMap model) {
    if (bindingResult.hasErrors()) {
      return showAllPosts(post)
    }

    this.postRepository.save(post)
    new ModelAndView('redirect:/posts', [:])
  }

}
