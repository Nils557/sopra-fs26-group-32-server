package ch.uzh.ifi.hase.soprafs26.controller;                                                                                                                                                           
  
  import java.util.ArrayList;
  import java.util.List;

  import org.springframework.http.HttpStatus;                                                                                                                                                               import org.springframework.web.bind.annotation.DeleteMapping;
  import org.springframework.web.bind.annotation.GetMapping;                                                                                                                                              
  import org.springframework.web.bind.annotation.PathVariable;
  import org.springframework.web.bind.annotation.PostMapping;                                                                                                                                               import org.springframework.web.bind.annotation.RequestBody;
  import org.springframework.web.bind.annotation.ResponseBody;                                                                                                                                            
  import org.springframework.web.bind.annotation.ResponseStatus;                                                                                                                                            import org.springframework.web.bind.annotation.RestController;
                                                                                                                                                                                                          
  import ch.uzh.ifi.hase.soprafs26.entity.User;
  import ch.uzh.ifi.hase.soprafs26.rest.dto.UserGetDTO;
  import ch.uzh.ifi.hase.soprafs26.rest.dto.UserPostDTO;
  import ch.uzh.ifi.hase.soprafs26.rest.mapper.DTOMapper;                                                                                                                                                   import ch.uzh.ifi.hase.soprafs26.service.DisconnectGraceService;
  import ch.uzh.ifi.hase.soprafs26.service.LobbyService;
  import ch.uzh.ifi.hase.soprafs26.service.UserService;                                                                                                                                                     import jakarta.servlet.http.HttpSession;
                                                                                                                                                                                                            @RestController
  public class UserController {                                                                                                                                                                           
  
      private final UserService userService;
      private final LobbyService lobbyService;
      private final DisconnectGraceService graceService;

      UserController(UserService userService, LobbyService lobbyService, DisconnectGraceService graceService) {                                                                                                                                          this.userService = userService;
          this.lobbyService = lobbyService;
          this.graceService = graceService;
      }

      @GetMapping("/users")
      @ResponseStatus(HttpStatus.OK)
      @ResponseBody
      public List<UserGetDTO> getAllUsers() {                                                                                                                                                                       List<User> users = userService.getUsers();
          List<UserGetDTO> userGetDTOs = new ArrayList<>();                                                                                                                                               
          for (User user : users) {                                                                                                                                                                                     userGetDTOs.add(DTOMapper.INSTANCE.convertEntityToUserGetDTO(user));
          }                                                                                                                                                                                                         return userGetDTOs;
      }                                                                                                                                                                                                   
  
      @PostMapping("/users")
      @ResponseStatus(HttpStatus.CREATED)
      @ResponseBody
      public UserGetDTO createUser(@RequestBody UserPostDTO userPostDTO, HttpSession session) {
          User userInput = DTOMapper.INSTANCE.convertUserPostDTOtoEntity(userPostDTO);                                                                                                                    
          User createdUser = userService.createUser(userInput);                                                                                                                                                     session.setAttribute("userId", createdUser.getId());                                                                                                                                            
          return DTOMapper.INSTANCE.convertEntityToUserGetDTO(createdUser);                                                                                                                               
      }

      @PostMapping("/logout")                                                                                                                                                                                   public void logout(HttpSession session) {
          session.invalidate();                                                                                                                                                                           
      }

      @DeleteMapping("/users/{id}")
      @ResponseStatus(HttpStatus.NO_CONTENT)
      public void deleteUser(@PathVariable Long id) {                                                                                                                                                               // Explicit logout: run cleanup now and cancel any grace-period disconnect for this user.
          graceService.cancel(id);
          lobbyService.handlePlayerDisconnect(id);
          userService.deleteUser(id);
      }
  }