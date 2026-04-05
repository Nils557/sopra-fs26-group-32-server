package ch.uzh.ifi.hase.soprafs26.controller;

  import org.springframework.http.HttpStatus;
  import org.springframework.web.bind.annotation.PathVariable;
  import org.springframework.web.bind.annotation.PostMapping;
  import org.springframework.web.bind.annotation.RequestBody;
  import org.springframework.web.bind.annotation.ResponseBody;
  import org.springframework.web.bind.annotation.ResponseStatus;
  import org.springframework.web.bind.annotation.RestController;

  import ch.uzh.ifi.hase.soprafs26.entity.Lobby;
  import ch.uzh.ifi.hase.soprafs26.rest.dto.LobbyGetDTO;
  import ch.uzh.ifi.hase.soprafs26.rest.dto.LobbyPostDTO;
  import ch.uzh.ifi.hase.soprafs26.rest.mapper.DTOMapper;
  import ch.uzh.ifi.hase.soprafs26.service.LobbyService;

  import java.util.Map;

  @RestController
  public class LobbyController {

      private final LobbyService lobbyService;

      LobbyController(LobbyService lobbyService) {
          this.lobbyService = lobbyService;
      }

      @PostMapping("/lobbies")
      @ResponseStatus(HttpStatus.CREATED)
      @ResponseBody
      public LobbyGetDTO createLobby(@RequestBody LobbyPostDTO lobbyPostDTO) {
          Lobby lobbyInput = DTOMapper.INSTANCE.convertLobbyPostDTOtoEntity(lobbyPostDTO);
          Lobby createdLobby = lobbyService.createLobby(lobbyInput);
          return DTOMapper.INSTANCE.convertEntityToLobbyGetDTO(createdLobby);
      }

      @PostMapping("/lobbies/{code}/players")
      @ResponseStatus(HttpStatus.CREATED)
      public void joinLobby(@PathVariable String code, @RequestBody Map<String, Long> body) {
          Long userId = body.get("userId");
          lobbyService.joinLobby(code, userId);
      }
  }
