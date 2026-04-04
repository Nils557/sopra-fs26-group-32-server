package ch.uzh.ifi.hase.soprafs26.controller;

  import java.util.ArrayList;
  import java.util.List;

  import org.springframework.http.HttpStatus;
  import org.springframework.web.bind.annotation.DeleteMapping;
  import org.springframework.web.bind.annotation.GetMapping;
  import org.springframework.web.bind.annotation.PathVariable;
  import org.springframework.web.bind.annotation.PostMapping;
  import org.springframework.web.bind.annotation.RequestBody;
  import org.springframework.web.bind.annotation.ResponseBody;
  import org.springframework.web.bind.annotation.ResponseStatus;
  import org.springframework.web.bind.annotation.RestController;

  import ch.uzh.ifi.hase.soprafs26.entity.User;
  import ch.uzh.ifi.hase.soprafs26.rest.dto.UserGetDTO;
  import ch.uzh.ifi.hase.soprafs26.rest.dto.UserPostDTO;
  import ch.uzh.ifi.hase.soprafs26.rest.mapper.DTOMapper;
  import ch.uzh.ifi.hase.soprafs26.service.UserService;
  import jakarta.servlet.http.HttpSession;

  /**
   * User Controller
   * This class is responsible for handling all REST request that are related to
   * the user.
   * The controller will receive the request and delegate the execution to the
   * UserService and finally return the result.
   */
  @RestController
  public class UserController {

      private final UserService userService;

      UserController(UserService userService) {
          this.userService = userService;
      }

      @GetMapping("/users")
      @ResponseStatus(HttpStatus.OK)
      @ResponseBody
      public List<UserGetDTO> getAllUsers() {
          List<User> users = userService.getUsers();
          List<UserGetDTO> userGetDTOs = new ArrayList<>();
          for (User user : users) {
              userGetDTOs.add(DTOMapper.INSTANCE.convertEntityToUserGetDTO(user));
          }
          return userGetDTOs;
      }

      @PostMapping("/users")
      @ResponseStatus(HttpStatus.CREATED)
      @ResponseBody
      public UserGetDTO createUser(@RequestBody UserPostDTO userPostDTO, HttpSession session) {
          User userInput = DTOMapper.INSTANCE.convertUserPostDTOtoEntity(userPostDTO);
          User createdUser = userService.createUser(userInput);
          session.setAttribute("userId", createdUser.getId());
          return DTOMapper.INSTANCE.convertEntityToUserGetDTO(createdUser);
      }

      @PostMapping("/logout")
      public void logout(HttpSession session) {
          session.invalidate();
      }

      @DeleteMapping("/users/{id}")
      @ResponseStatus(HttpStatus.NO_CONTENT)
      public void deleteUser(@PathVariable Long id) {
          userService.deleteUser(id);
      }
  }
