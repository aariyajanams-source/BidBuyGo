package com.bidbuygo.user;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /** Atomic in the database, so concurrent sales by the same seller can't lose an increment. */
    @Modifying(flushAutomatically = true)
    @Query("update User u set u.completedOrders = u.completedOrders + 1 where u.id = :id")
    int incrementCompletedOrders(@Param("id") Long id);
}
