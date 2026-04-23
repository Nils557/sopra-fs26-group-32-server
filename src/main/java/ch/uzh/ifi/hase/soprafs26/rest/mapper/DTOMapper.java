package ch.uzh.ifi.hase.soprafs26.rest.mapper;

import org.mapstruct.*;
import org.mapstruct.factory.Mappers;
import java.time.Instant;

import ch.uzh.ifi.hase.soprafs26.entity.User;
import ch.uzh.ifi.hase.soprafs26.entity.Lobby;
import ch.uzh.ifi.hase.soprafs26.rest.dto.LobbyGetDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.LobbyPostDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.UserGetDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.UserPostDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.LobbyStartGetDTO;
import ch.uzh.ifi.hase.soprafs26.entity.Answer;
import ch.uzh.ifi.hase.soprafs26.rest.dto.AnswerGetDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.AnswerPostDTO;


/**
 * DTOMapper
 * This class is responsible for generating classes that will automatically
 * transform/map the internal representation
 * of an entity (e.g., the User) to the external/API representation (e.g.,
 * UserGetDTO for getting, UserPostDTO for creating)
 * and vice versa.
 * Additional mappers can be defined for new entities.
 * Always created one mapper for getting information (GET) and one mapper for
 * creating information (POST).
 */
@Mapper
public interface DTOMapper {

	DTOMapper INSTANCE = Mappers.getMapper(DTOMapper.class);

	@Mapping(source = "username", target = "username")
	User convertUserPostDTOtoEntity(UserPostDTO userPostDTO);

	@Mapping(source = "id", target = "id")
	@Mapping(source = "username", target = "username")
	@Mapping(source = "createdAt", target = "createdAt")
	UserGetDTO convertEntityToUserGetDTO(User user);


    // Lobby mappings
    @Mapping(source = "hostUserId", target = "hostUserId")
    @Mapping(source = "maxPlayers", target = "maxPlayers")
    @Mapping(source = "totalRounds", target = "totalRounds")
    Lobby convertLobbyPostDTOtoEntity(LobbyPostDTO lobbyPostDTO);

    @Mapping(source = "id", target = "id")
    @Mapping(source = "lobbyCode", target = "lobbyCode")
    @Mapping(source = "maxPlayers", target = "maxPlayers")
    @Mapping(source = "totalRounds", target = "totalRounds")
    @Mapping(source = "status", target = "status")
    @Mapping(source = "hostUserId", target = "hostUserId")
    LobbyGetDTO convertEntityToLobbyGetDTO(Lobby lobby);

    // LobbyStartGetDTO mappings
    @Mapping(source = "lobbyCode", target = "lobbyCode")
    @Mapping(source = "status", target = "status")
    LobbyStartGetDTO convertEntityToLobbyStartGetDTO(Lobby lobby);

    @Mapping(source = "latitude", target = "latitude")
    @Mapping(source = "longitude", target = "longitude")
    Answer convertAnswerPostDTOtoEntity(AnswerPostDTO answerPostDTO);

    @Mapping(source = "id", target = "id")
    @Mapping(source = "latitude", target = "latitude")
    @Mapping(source = "longitude", target = "longitude")
    @Mapping(source = "scoreResult", target = "scoreResult")
    @Mapping(source = "pointsAwarded", target = "pointsAwarded")
    @Mapping(source = "submittedAt", target = "submittedAt")
    AnswerGetDTO convertEntityToAnswerGetDTO(Answer answer);
}


