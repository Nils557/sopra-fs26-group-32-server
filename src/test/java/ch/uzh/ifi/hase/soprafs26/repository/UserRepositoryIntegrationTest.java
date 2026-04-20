package ch.uzh.ifi.hase.soprafs26.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import ch.uzh.ifi.hase.soprafs26.constant.UserStatus;
import ch.uzh.ifi.hase.soprafs26.entity.User;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@DataJpaTest
public class UserRepositoryIntegrationTest {

	@Autowired
	private TestEntityManager entityManager;

	@Autowired
	private UserRepository userRepository;

	@Test
	public void findByName_success() {
		// given
		User user = new User();
		user.setUsername("firstname@lastname");

		entityManager.persist(user);
		entityManager.flush();

		// when
		User found = userRepository.findByUsername(user.getUsername());

		// then
		assertNotNull(found.getId());
		assertEquals(found.getUsername(), user.getUsername());
	}

	/**
	 * US1 #54 — User entity + repository.
	 * Confirms that saving a fresh User populates the generated ID and
	 * that the @PrePersist hook on User.onCreate sets createdAt. Without
	 * this test, a future refactor could accidentally drop the @PrePersist
	 * annotation and we'd persist users with null createdAt without noticing.
	 */
	@Test
	public void save_persistsIdAndSetsCreatedAtViaPrePersist() {
		// given — a brand-new User with no ID and no createdAt
		User user = new User();
		user.setUsername("newcomer");
		assertNull(user.getId());
		assertNull(user.getCreatedAt());

		// when — save + flush so the @PrePersist callback fires and the identity
		// generator assigns an ID
		User saved = userRepository.saveAndFlush(user);

		// then — both fields are populated
		assertNotNull(saved.getId(), "ID should be generated on save");
		assertNotNull(saved.getCreatedAt(), "createdAt should be set by @PrePersist");
	}
}
