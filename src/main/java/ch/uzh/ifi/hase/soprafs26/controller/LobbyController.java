package ch.uzh.ifi.hase.soprafs26.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import ch.uzh.ifi.hase.soprafs26.entity.Lobby;
import ch.uzh.ifi.hase.soprafs26.entity.User;
import ch.uzh.ifi.hase.soprafs26.rest.dto.LobbyGetDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.LobbyPostDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.LobbyStartGetDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.UserGetDTO;
import ch.uzh.ifi.hase.soprafs26.rest.mapper.DTOMapper;
import ch.uzh.ifi.hase.soprafs26.service.LobbyService;

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

    @GetMapping("/lobbies/{code}")
    @ResponseStatus(HttpStatus.OK)
    @ResponseBody
    public LobbyGetDTO getLobby(@PathVariable String code) {
        return lobbyService.getLobby(code);
    }

    @PostMapping("/lobbies/{code}/players")
    @ResponseStatus(HttpStatus.CREATED)
    @ResponseBody
    public UserGetDTO joinLobby(@PathVariable String code, @RequestBody Map<String, Long> body) {
        Long userId = body.get("userId");
        User user = lobbyService.joinLobby(code, userId);
        return DTOMapper.INSTANCE.convertEntityToUserGetDTO(user);
    }

    @GetMapping("/lobbies/{code}/players")
    @ResponseStatus(HttpStatus.OK)
    @ResponseBody
    public List<String> getPlayers(@PathVariable String code) {
        return lobbyService.getPlayers(code);
    }

    @PostMapping("/lobbies/{code}/start")
    @ResponseStatus(HttpStatus.CREATED)
    @ResponseBody
    public LobbyStartGetDTO startGame(@PathVariable String code, @RequestBody Map<String, Long> body) {
        Long hostUserId = body.get("hostUserId");
        Lobby startedLobby = lobbyService.startGame(code, hostUserId);
        return DTOMapper.INSTANCE.convertEntityToLobbyStartGetDTO(startedLobby);
    }
}