package com.bidbuygo.listing;

import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.bidbuygo.auth.AuthUser;
import com.bidbuygo.common.error.ApiException;
import com.bidbuygo.common.persistence.Locks;
import com.bidbuygo.policy.EligibilityResult;
import com.bidbuygo.policy.EligibilityService;
import com.bidbuygo.user.Role;
import com.bidbuygo.user.UserService;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ListingService {

    private final ListingRepository listingRepository;
    private final EligibilityService eligibilityService;
    private final UserService userService;
    private final EntityManager entityManager;
    private final Clock clock;

    /** Validates (400), checks auction eligibility (422), then saves an ACTIVE listing. */
    @Transactional
    public ListingResponse create(Long sellerId, ListingDraft draft) {
        EligibilityResult eligibility = check(sellerId, draft);
        if (!eligibility.eligible()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Listing is not eligible for auction",
                    eligibility.reasons());
        }
        Listing listing = listingRepository.save(Listing.create(sellerId, draft, clock.instant()));
        return ListingResponse.from(listing);
    }

    /** Dry run of create: same validation and eligibility, nothing saved. */
    @Transactional(readOnly = true)
    public EligibilityResult check(Long sellerId, ListingDraft draft) {
        List<String> errors = ListingDraftValidator.validate(draft, clock.instant());
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid listing", errors);
        }
        // Load the seller fresh: verified/completedOrders may have changed since the token was issued.
        return eligibilityService.check(userService.get(sellerId), draft);
    }

    @Transactional(readOnly = true)
    public List<ListingResponse> listActive(ListingType type, String category) {
        String normalized = category == null || category.isBlank() ? null : Listing.normalizeCategory(category);
        return listingRepository.search(ListingStatus.ACTIVE, type, normalized).stream()
                .map(ListingResponse::from)
                .toList();
    }

    /**
     * REJECTED listings are visible only to their seller and admins; to everyone else they don't exist.
     * viewer may be null (anonymous); the response says whether a signed-in viewer is the high bidder.
     */
    @Transactional(readOnly = true)
    public ListingResponse get(Long id, AuthUser viewer) {
        return ListingResponse.from(findVisible(id, viewer), viewer);
    }

    /** Throws 404 unless the listing exists and the viewer may see it. */
    @Transactional(readOnly = true)
    public Listing findVisible(Long id, AuthUser viewer) {
        Listing listing = listingRepository.findById(id).orElseThrow(() -> notFound(id));
        if (listing.getStatus() == ListingStatus.REJECTED && !canSeeRejected(listing, viewer)) {
            throw notFound(id);
        }
        return listing;
    }

    @Transactional(readOnly = true)
    public List<ListingResponse> listForAdmin(ListingStatus status) {
        List<Listing> listings = status == null
                ? listingRepository.findAllByOrderByIdDesc()
                : listingRepository.findByStatusOrderByIdDesc(status);
        return listings.stream().map(ListingResponse::from).toList();
    }

    @Transactional
    public ListingResponse reject(Long id, String reason) {
        Listing listing = lockForUpdate(id);
        if (listing.getStatus() != ListingStatus.ACTIVE) {
            throw new ApiException(HttpStatus.CONFLICT, "Listing cannot be rejected",
                    "Only ACTIVE listings can be rejected; this listing is " + listing.getStatus());
        }
        if (listing.getHighBidderId() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "Listing cannot be rejected",
                    "This auction already has bids; rejecting it would require refunding held bids");
        }
        listing.reject(reason.trim());
        return ListingResponse.from(listing);
    }

    /**
     * Step one of any money or bid flow: lock the listing row before touching wallets. Returns state read under the
     * lock even if the listing was loaded earlier in the transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Listing lockForUpdate(Long id) {
        Listing listing = listingRepository.findByIdForUpdate(id).orElseThrow(() -> notFound(id));
        return Locks.ensureWriteLocked(entityManager, listing);
    }

    /** Ids of ACTIVE auctions whose end time has passed. No locks; callers re-check under lockForUpdate. */
    @Transactional(readOnly = true)
    public List<Long> findDueAuctionIds() {
        return listingRepository.findDueAuctionIds(clock.instant());
    }

    @Transactional(readOnly = true)
    public Map<Long, String> titlesById(Collection<Long> ids) {
        return listingRepository.findAllById(ids).stream().collect(Collectors.toMap(Listing::getId, Listing::getTitle));
    }

    @Transactional(readOnly = true)
    public boolean existsForSeller(Long sellerId, String title) {
        return listingRepository.existsBySellerIdAndTitle(sellerId, title);
    }

    private static boolean canSeeRejected(Listing listing, AuthUser viewer) {
        return viewer != null && (viewer.role() == Role.ADMIN || viewer.id().equals(listing.getSellerId()));
    }

    private static ApiException notFound(Long id) {
        return new ApiException(HttpStatus.NOT_FOUND, "Listing not found", "No listing with id " + id);
    }
}
