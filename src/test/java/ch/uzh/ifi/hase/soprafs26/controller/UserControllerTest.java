package ch.uzh.ifi.hase.soprafs26.controller;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;


import ch.uzh.ifi.hase.soprafs26.constant.UserStatus;
import ch.uzh.ifi.hase.soprafs26.entity.User;
import ch.uzh.ifi.hase.soprafs26.rest.dto.UserPostDTO;
import ch.uzh.ifi.hase.soprafs26.service.UserService;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collections;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import ch.uzh.ifi.hase.soprafs26.service.LobbyService;

/**
 * UserControllerTest
 * This is a WebMvcTest which allows to test the UserController i.e. GET/POST
 * request without actually sending them over the network.
 * This tests if the UserController works.
 */
@WebMvcTest(UserController.class)
public class UserControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private UserService userService;

	@MockitoBean  
	private LobbyService lobbyService;

	@Test
	public void givenUsers_whenGetUsers_thenReturnJsonArray() throws Exception {
		// given
		User user = new User();
		user.setUsername("firstname@lastname");

		List<User> allUsers = Collections.singletonList(user);

		// this mocks the UserService -> we define above what the userService should
		// return when getUsers() is called
		given(userService.getUsers()).willReturn(allUsers);

		// when
		MockHttpServletRequestBuilder getRequest = get("/users").contentType(MediaType.APPLICATION_JSON);

		// then
		mockMvc.perform(getRequest).andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(1)))
				.andExpect(jsonPath("$[0].username", is(user.getUsername())));
	}

	/**
	 * US1 #55 — POST /users endpoint for user creation.
	 *
	 * The login page sends a UserPostDTO containing just the chosen
	 * username. The controller must:
	 *   1. Accept the request with Content-Type application/json and
	 *      deserialize the body into a UserPostDTO.
	 *   2. Delegate to UserService.createUser, which persists a new User
	 *      (DTOMapper handles UserPostDTO -> User conversion; the mapper
	 *      is tested separately in DTOMapperTest).
	 *   3. Return HTTP 201 CREATED (not 200) — this status is how the
	 *      frontend distinguishes a newly-minted user from other flows.
	 *   4. Return a UserGetDTO body containing the generated id and the
	 *      original username — the frontend stores these in
	 *      sessionStorage as "userId" / "username" and uses them for
	 *      every subsequent API call + the WS CONNECT header.
	 *
	 * This test mocks the service layer (no real DB) so it only exercises
	 * the HTTP binding + DTO serialization + status code contract.
	 * Uniqueness and blank-username validation live on the service side
	 * and are covered by UserServiceTest.
	 */
	@Test
	public void createUser_validInput_userCreated() throws Exception {
		// given — a fresh UserPostDTO from the client and the service's expected
		// "persisted" return value (with a generated id)
		User user = new User();
		user.setId(1L);
		user.setUsername("testUsername");

		UserPostDTO userPostDTO = new UserPostDTO();
		userPostDTO.setUsername("testUsername");

		given(userService.createUser(Mockito.any())).willReturn(user);

		// when — POST /users with the JSON body
		MockHttpServletRequestBuilder postRequest = post("/users")
				.contentType(MediaType.APPLICATION_JSON)
				.content(asJsonString(userPostDTO));

		// then — 201 CREATED + the UserGetDTO JSON with id + username
		mockMvc.perform(postRequest)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id", is(user.getId().intValue())))
				.andExpect(jsonPath("$.username", is(user.getUsername())));
	}

	/**
	 * US1 #57 — Automatic user deletion on session close (triggered by the
	 * frontend Logout button calling DELETE /users/{id}).
	 *
	 * The controller must:
	 *   1. Return HTTP 204 No Content.
	 *   2. Call LobbyService.handlePlayerDisconnect FIRST so any lobby
	 *      membership / host role is cleaned up (lobby deletion, round
	 *      cleanup, lobby_players join rows cleared).
	 *   3. Then call UserService.deleteUser so the users row deletion
	 *      doesn't hit a foreign-key violation from surviving join rows.
	 *
	 * InOrder verification here is load-bearing — it was a Postgres FK
	 * constraint violation caused by calling these in the wrong order that
	 * motivated the explicit join-table clear in LobbyService.removePlayer.
	 */
	@Test
	public void deleteUser_validId_returnsNoContentAndCascadesInOrder() throws Exception {
		// given — a user id the client is logging out
		Long userId = 42L;

		// when — DELETE /users/{id}
		mockMvc.perform(delete("/users/{id}", userId))
				.andExpect(status().isNoContent());

		// then — both services were called, in the correct order
		org.mockito.InOrder inOrder = Mockito.inOrder(lobbyService, userService);
		inOrder.verify(lobbyService).handlePlayerDisconnect(userId);
		inOrder.verify(userService).deleteUser(userId);
	}

	/**
	 * Helper Method to convert userPostDTO into a JSON string such that the input
	 * can be processed
	 * Input will look like this: {"name": "Test User", "username": "testUsername"}
	 * 
	 * @param object
	 * @return string
	 */
	private String asJsonString(final Object object) {
		try {
			return new ObjectMapper().writeValueAsString(object);
		} catch (JacksonException e) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					String.format("The request body could not be created.%s", e.toString()));
		}
	}
}