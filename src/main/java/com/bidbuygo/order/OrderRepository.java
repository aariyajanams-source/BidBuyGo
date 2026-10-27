package com.bidbuygo.order;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, Long> {

    @Query("select o from Order o where o.buyerId = :userId or o.sellerId = :userId order by o.createdAt desc, o.id desc")
    List<Order> findInvolving(@Param("userId") Long userId);

    Optional<Order> findByListingId(Long listingId);
}
