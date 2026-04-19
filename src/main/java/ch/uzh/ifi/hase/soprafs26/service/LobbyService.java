package ch.uzh.ifi.hase.soprafs26.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import ch.uzh.ifi.hase.soprafs26.constant.LobbyStatus;
import ch.uzh.ifi.hase.soprafs26.entity.Lobby;
import ch.uzh.ifi.hase.soprafs26.entity.User;
import ch.uzh.ifi.hase.soprafs26.repository.LobbyRepository;
import ch.uzh.ifi.hase.soprafs26.repository.UserRepository;
import ch.uzh.ifi.hase.soprafs26.rest.dto.LobbyGetDTO;
import ch.uzh.ifi.hase.soprafs26.rest.mapper.DTOMapper;

@Service
@Transactional
public class LobbyService {

    private final Logger log = LoggerFactory.getLogger(LobbyService.class);
    private final LobbyRepository lobbyRepository;
    private final UserRepository userRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final RoundService roundService;

    public LobbyService(@Qualifier("lobbyRepository") LobbyRepository lobbyRepository,
                        @Qualifier("userRepository") UserRepository userRepository,
                        SimpMessagingTemplate messagingTemplate,
                        RoundService roundService) {
        this.lobbyRepository = lobbyRepository;
        this.userRepository = userRepository;
        this.messagingTemplate = messagingTemplate;
        this.roundService = roundService;
    }

    public Lobby createLobby(Lobby newLobby) {
        User host = userRepository.findById(newLobby.getHostUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Host user not found"));
        if (newLobby.getTotalRounds() < 1 || newLobby.getTotalRounds() > 10) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Rounds must be between 1 and 10");
        }
        if (newLobby.getMaxPlayers() < 2 || newLobby.getMaxPlayers() > 10) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Max players must be between 2 and 10");
        }

        newLobby.setLobbyCode(generateLobbyCode());
        newLobby.setStatus(LobbyStatus.WAITING);
        newLobby.getPlayers().add(host);
        newLobby = lobbyRepository.save(newLobby);
        lobbyRepository.flush();

        log.debug("Created lobby with code: {}", newLobby.getLobbyCode());
        return newLobby;
    }

    public LobbyGetDTO getLobby(String lobbyCode) {
        Lobby lobby = lobbyRepository.findByLobbyCode(lobbyCode);
        if (lobby == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Lobby not found");
        }
        LobbyGetDTO dto = DTOMapper.INSTANCE.convertEntityToLobbyGetDTO(lobby);
        userRepository.findById(lobby.getHostUserId())
                .ifPresent(host -> dto.setHostUsername(host.getUsername()));
        return dto;
    }

    public User joinLobby(String lobbyCode, Long userId) {
        Lobby lobby = lobbyRepository.findByLobbyCode(lobbyCode);

        if (lobby == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Lobby not found");
        }
        if (lobby.getStatus() != LobbyStatus.WAITING) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Game has already started");
        }
        if (lobby.getPlayers().size() >= lobby.getMaxPlayers()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Lobby is full");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        boolean alreadyJoined = lobby.getPlayers().stream()
                .anyMatch(p -> p.getId().equals(userId));
        if (alreadyJoined) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "User already joined this lobby");
        }
        lobby.getPlayers().add(user);
        lobbyRepository.save(lobby);
        lobbyRepository.flush();

        List<String> usernames = lobby.getPlayers().stream()
                .map(User::getUsername)
                .toList();
        messagingTemplate.convertAndSend(
                "/topic/lobby/" + lobbyCode + "/players",
                usernames
        );
        return user;
    }

    public List<String> getPlayers(String lobbyCode) {
        Lobby lobby = lobbyRepository.findByLobbyCode(lobbyCode);
        if (lobby == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Lobby not found");
        }
        return lobby.getPlayers().stream()
                .map(User::getUsername)
                .toList();
    }

    public boolean isHost(Long userId) {
        return lobbyRepository.findByPlayers_Id(userId)
                .map(lobby -> lobby.getHostUserId().equals(userId))
                .orElse(false);
    }

    @Transactional
    public void handlePlayerDisconnect(Long userId) {
        lobbyRepository.findByPlayers_Id(userId).ifPresent(lobby -> removePlayer(lobby, userId));
    }

    private void removePlayer(Lobby lobby, Long userId) {
        String lobbyCode = lobby.getLobbyCode();
        if (userId.equals(lobby.getHostUserId())) {
            lobbyRepository.delete(lobby);
            lobbyRepository.flush();
            messagingTemplate.convertAndSend("/topic/lobby/" + lobbyCode + "/disconnect", "HOST_DISCONNECTED");
        } else {
            lobby.getPlayers().removeIf(p -> p.getId().equals(userId));
            lobbyRepository.save(lobby);
            lobbyRepository.flush();

            List<String> usernames = lobby.getPlayers().stream()
                    .map(User::getUsername)
                    .toList();
            messagingTemplate.convertAndSend("/topic/lobby/" + lobbyCode + "/players", usernames);
        }
    }

    private String generateLobbyCode() {
        String code;
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        do {
            char l1 = alphabet.charAt((int) (Math.random() * 26));
            char l2 = alphabet.charAt((int) (Math.random() * 26));
            String letters = "" + l1 + l2;
            String numbers = String.valueOf((int) (Math.random() * 9000) + 1000);
            code = letters + "-" + numbers;
        } while (lobbyRepository.findByLobbyCode(code) != null);
        return code;
    }

    public Lobby startGame(String lobbyCode, Long hostUserId) {
        Lobby lobby = lobbyRepository.findByLobbyCode(lobbyCode);

        if (lobby == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Lobby not found");
        }
        if (userRepository.findById(hostUserId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Host user not found");
        }
        if (!lobby.getHostUserId().equals(hostUserId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Requester is not the host");
        }
        if (lobby.getStatus() == LobbyStatus.INGAME) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Game has already started");
        }
        if (lobby.getPlayers().size() < 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Fewer than 2 players in lobby");
        }

        lobby.setStatus(LobbyStatus.INGAME);
        lobby = lobbyRepository.save(lobby);
        lobbyRepository.flush();
        roundService.startRoundWithTimer(lobbyCode);

        messagingTemplate.convertAndSend(
                "/topic/lobby/" + lobbyCode + "/start",
                DTOMapper.INSTANCE.convertEntityToLobbyStartGetDTO(lobby));
        log.debug("Game started in lobby: {}", lobbyCode);
        return lobby;
    }
}