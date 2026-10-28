package com.bidbuygo.bid;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BidRepository extends JpaRepository<Bid, Long> {

    List<Bid> findByListingIdOrderByCreatedAtDescIdDesc(Long listingId);

    long countByListingId(Long listingId);
}
