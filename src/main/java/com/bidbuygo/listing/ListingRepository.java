package com.bidbuygo.listing;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ListingRepository extends JpaRepository<Listing, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from Listing l where l.id = :id")
    Optional<Listing> findByIdForUpdate(@Param("id") Long id);

    /** type and category are optional; category must already be normalized. */
    @Query("""
            select l from Listing l
            where l.status = :status
              and (:type is null or l.type = :type)
              and (:category is null or l.category = :category)
            order by l.createdAt desc, l.id desc
            """)
    List<Listing> search(@Param("status") ListingStatus status,
                         @Param("type") ListingType type,
                         @Param("category") String category);

    /** Plain read, no locks: the settlement service re-checks each one under the listing lock. */
    @Query("""
            select l.id from Listing l
            where l.type = com.bidbuygo.listing.ListingType.AUCTION
              and l.status = com.bidbuygo.listing.ListingStatus.ACTIVE
              and l.endsAt <= :now
            order by l.endsAt, l.id
            """)
    List<Long> findDueAuctionIds(@Param("now") Instant now);

    List<Listing> findAllByOrderByIdDesc();

    List<Listing> findByStatusOrderByIdDesc(ListingStatus status);

    boolean existsBySellerIdAndTitle(Long sellerId, String title);
}
