package com.ordermanager.security;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByUsername(String username);

    boolean existsByUsername(String username);

    List<AppUser> findAllByOrderByUsername();

    /**
     * Counts a failed login in one atomic UPDATE (the row lock serialises concurrent attempts, so none is lost).
     * The attempt that reaches {@code max} locks the account and resets the counter.
     */
    @Modifying
    @Query(value = """
            update app_user
               set failed_attempts = case when failed_attempts + 1 >= :max then 0 else failed_attempts + 1 end,
                   locked_until    = case when failed_attempts + 1 >= :max then :lockUntil else locked_until end
             where username = :username""", nativeQuery = true)
    int recordFailedLogin(@Param("username") String username, @Param("max") int max,
                          @Param("lockUntil") Instant lockUntil);

    @Modifying
    @Query(value = "update app_user set failed_attempts = 0, locked_until = null "
            + "where username = :username and (failed_attempts <> 0 or locked_until is not null)", nativeQuery = true)
    int resetFailedLogins(@Param("username") String username);
}
