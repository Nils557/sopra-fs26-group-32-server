package ch.uzh.ifi.hase.soprafs26.rest.mapper;

import org.mapstruct.Mapping;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;
import java.util.List;

import ch.uzh.ifi.hase.soprafs26.entity.User;
import ch.uzh.ifi.hase.soprafs26.entity.Lobby;
import ch.uzh.ifi.hase.soprafs26.entity.Round;
import ch.uzh.ifi.hase.soprafs26.rest.dto.LobbyGetDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.LobbyPostDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.UserGetDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.UserPostDTO;
import ch.uzh.ifi.hase.soprafs26.service.ScoringService;
import ch.uzh.ifi.hase.soprafs26.rest.dto.LobbyStartGetDTO;
import ch.uzh.ifi.hase.soprafs26.entity.Answer;
import ch.uzh.ifi.hase.soprafs26.rest.dto.AnswerGetDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.AnswerPostDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.RoundSummaryGetDTO;


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
	@Mapping(target = "id", ignore = true)
	@Mapping(target = "createdAt", ignore = true)
	@Mapping(target = "status", ignore = true)
	User convertUserPostDTOtoEntity(UserPostDTO userPostDTO);

	@Mapping(source = "id", target = "id")
	@Mapping(source = "username", target = "username")
	@Mapping(source = "createdAt", target = "createdAt")
	UserGetDTO convertEntityToUserGetDTO(User user);


    // Lobby mappings
    @Mapping(source = "hostUserId", target = "hostUserId")
    @Mapping(source = "maxPlayers", target = "maxPlayers")
    @Mapping(source = "totalRounds", target = "totalRounds")
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "lobbyCode", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "players", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    Lobby convertLobbyPostDTOtoEntity(LobbyPostDTO lobbyPostDTO);

    @Mapping(source = "id", target = "id")
    @Mapping(source = "lobbyCode", target = "lobbyCode")
    @Mapping(source = "maxPlayers", target = "maxPlayers")
    @Mapping(source = "totalRounds", target = "totalRounds")
    @Mapping(source = "status", target = "status")
    @Mapping(source = "hostUserId", target = "hostUserId")
    @Mapping(target = "hostUsername", ignore = true)
    LobbyGetDTO convertEntityToLobbyGetDTO(Lobby lobby);

    // LobbyStartGetDTO mappings
    @Mapping(source = "lobbyCode", target = "lobbyCode")
    @Mapping(source = "status", target = "status")
    LobbyStartGetDTO convertEntityToLobbyStartGetDTO(Lobby lobby);

    @Mapping(source = "latitude", target = "latitude")
    @Mapping(source = "longitude", target = "longitude")
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "scoreResult", ignore = true)
    @Mapping(target = "pointsAwarded", ignore = true)
    @Mapping(target = "submittedAt", ignore = true)
    @Mapping(target = "round", ignore = true)
    @Mapping(target = "player", ignore = true)
    Answer convertAnswerPostDTOtoEntity(AnswerPostDTO answerPostDTO);

    @Mapping(source = "id", target = "id")
    @Mapping(source = "latitude", target = "latitude")
    @Mapping(source = "longitude", target = "longitude")
    @Mapping(source = "scoreResult", target = "scoreResult")
    @Mapping(source = "pointsAwarded", target = "pointsAwarded")
    @Mapping(source = "submittedAt", target = "submittedAt")
    AnswerGetDTO convertEntityToAnswerGetDTO(Answer answer);

    @Mapping(source = "round.id", target = "roundId")
    @Mapping(source = "round.targetLatitude", target = "correctLatitude")
    @Mapping(source = "round.targetLongitude", target = "correctLongitude")
@Mapping(source = "round.targetCity", target = "correctCity")
    @Mapping(source = "round.targetCountry", target = "correctCountry") 
    @Mapping(source = "standings", target = "standings")
    RoundSummaryGetDTO convertToRoundSummaryGetDTO(Round round, List<ScoringService.PlayerStanding> standings);
}
