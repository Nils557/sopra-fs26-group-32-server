package ch.uzh.ifi.hase.soprafs26.service;                                                                                                                                                              
  
  import org.slf4j.Logger;
  import org.slf4j.LoggerFactory;
  import org.springframework.beans.factory.annotation.Qualifier;                                                                                                                                            import org.springframework.http.HttpStatus;
  import org.springframework.stereotype.Service;                                                                                                                                                          
  import org.springframework.transaction.annotation.Transactional;
  import org.springframework.web.server.ResponseStatusException;                                                                                                                                            
  import ch.uzh.ifi.hase.soprafs26.constant.LobbyStatus;                                                                                                                                                  
  import ch.uzh.ifi.hase.soprafs26.entity.Lobby;
  import ch.uzh.ifi.hase.soprafs26.repository.LobbyRepository;                                                                                                                                              import ch.uzh.ifi.hase.soprafs26.repository.UserRepository;
                                                                                                                                                                                                            @Service
  @Transactional                                                                                                                                                                                          
  public class LobbyService {

      private final Logger log = LoggerFactory.getLogger(LobbyService.class);                                                                                                                               
      private final LobbyRepository lobbyRepository;                                                                                                                                                      
      private final UserRepository userRepository;

      public LobbyService(@Qualifier("lobbyRepository") LobbyRepository lobbyRepository,
                          @Qualifier("userRepository") UserRepository userRepository) {
          this.lobbyRepository = lobbyRepository;                                                                                                                                                                   this.userRepository = userRepository;
      }                                                                                                                                                                                                   
  
      public Lobby createLobby(Lobby newLobby) {
          if (userRepository.findById(newLobby.getHostUserId()).isEmpty()) {
              throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Host user not found");                                                                                                             
          }                                                                                                                                                                                                         if (newLobby.getTotalRounds() < 1 || newLobby.getTotalRounds() > 10) {                                                                                                                          
              throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Rounds must be between 1 and 10");                                                                                               
          }                                                                                                                                                                                                         if (newLobby.getMaxPlayers() < 2) {
              throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Max players must be at least 2");                                                                                                
          }                                                                                                                                                                                                 
          newLobby.setLobbyCode(generateLobbyCode());                                                                                                                                                     
          newLobby.setStatus(LobbyStatus.WAITING);
          newLobby = lobbyRepository.save(newLobby);
          lobbyRepository.flush();                                                                                                                                                                          
          log.debug("Created lobby with code: {}", newLobby.getLobbyCode());                                                                                                                              
          return newLobby;
      }

      private String generateLobbyCode() {                                                                                                                                                                          String code;
          String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";                                                                                                                                                 
          do {    
              char l1 = alphabet.charAt((int)(Math.random() * 26));
              char l2 = alphabet.charAt((int)(Math.random() * 26));
              String letters = "" + l1 + l2;                                                                                                                                                                            String numbers = String.valueOf((int)(Math.random() * 9000) + 1000);
              code = letters + "-" + numbers;                                                                                                                                                             
          } while (lobbyRepository.findByLobbyCode(code) != null);
          return code;                                                                                                                                                                                          }
                                                                                                                                                                                                          
      public boolean isHost(Long userId) {
          return lobbyRepository.findByPlayers_Id(userId)
              .map(lobby -> lobby.getHostUserId().equals(userId))
              .orElse(false);                                                                                                                                                                                   }
                                                                                                                                                                                                          
      @Transactional
      public void handlePlayerDisconnect(Long userId) {
          Lobby lobby = lobbyRepository.findByPlayers_Id(userId).orElse(null);
                                                                                                                                                                                                                    if (lobby != null) {
              if (userId.equals(lobby.getHostUserId())) {                                                                                                                                                 
                  lobbyRepository.delete(lobby);
              } else {
                  lobby.getPlayers().removeIf(p -> p.getId().equals(userId));
                  lobbyRepository.save(lobby);
              }                                                                                                                                                                                                         lobbyRepository.flush();
          }                                                                                                                                                                                               
      }
  }