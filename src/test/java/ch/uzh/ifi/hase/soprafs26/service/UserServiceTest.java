package ch.uzh.ifi.hase.soprafs26.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.springframework.web.server.ResponseStatusException;

import ch.uzh.ifi.hase.soprafs26.entity.User;
import ch.uzh.ifi.hase.soprafs26.repository.UserRepository;

public class UserServiceTest {

	@Mock
	private UserRepository userRepository;

	@InjectMocks
	private UserService userService;

	private User testUser;

	@BeforeEach
	public void setup() {
		MockitoAnnotations.openMocks(this);

		// given
		testUser = new User();
		testUser.setId(1L);
		testUser.setUsername("testUsername");

		// when -> any object is being save in the userRepository -> return the dummy
		// testUser
		Mockito.when(userRepository.save(Mockito.any())).thenReturn(testUser);
	}

	@Test
	public void createUser_validInputs_success() {
		// when -> any object is being save in the userRepository -> return the dummy
		// testUser
		User createdUser = userService.createUser(testUser);

		// then
		Mockito.verify(userRepository, Mockito.times(1)).save(Mockito.any());

		assertEquals(testUser.getId(), createdUser.getId());
		assertEquals(testUser.getUsername(), createdUser.getUsername());
	}

	@Test
	public void createUser_duplicateName_throwsException() {
		// given -> a first user has already been created
		userService.createUser(testUser);

		// when -> setup additional mocks for UserRepository
		Mockito.when(userRepository.findByUsername(Mockito.any())).thenReturn(testUser);

		// then -> attempt to create second user with same user -> check that an error
		// is thrown
		assertThrows(ResponseStatusException.class, () -> userService.createUser(testUser));
	}

	@Test
	public void createUser_duplicateInputs_throwsException() {
		// given -> a first user has already been created
		userService.createUser(testUser);

		// when -> setup additional mocks for UserRepository
		Mockito.when(userRepository.findByUsername(Mockito.any())).thenReturn(testUser);

		// then -> attempt to create second user with same user -> check that an error
		// is thrown
		assertThrows(ResponseStatusException.class, () -> userService.createUser(testUser));
	}

	/**
	 * US1 #56 — Username validation.
	 * The service must reject blank usernames with HTTP 400 BAD_REQUEST
	 * before ever reaching the database. This guards the not-null + unique
	 * constraints on User.username and gives the client a deterministic
	 * error message instead of a constraint-violation stack trace.
	 */
	@Test
	public void createUser_blankUsername_throwsBadRequest() {
		// given a user with an empty username
		User blank = new User();
		blank.setUsername("");

		// when / then — createUser should throw 400 BAD_REQUEST
		ResponseStatusException ex = assertThrows(
				ResponseStatusException.class,
				() -> userService.createUser(blank));
		assertEquals(400, ex.getStatusCode().value());
	}

	/**
	 * US1 #56 — Username validation (null variant).
	 * Same contract as the blank case: null usernames are rejected with 400.
	 */
	@Test
	public void createUser_nullUsername_throwsBadRequest() {
		// given a user with a null username
		User nullName = new User();
		nullName.setUsername(null);

		// when / then
		ResponseStatusException ex = assertThrows(
				ResponseStatusException.class,
				() -> userService.createUser(nullName));
		assertEquals(400, ex.getStatusCode().value());
	}

}
