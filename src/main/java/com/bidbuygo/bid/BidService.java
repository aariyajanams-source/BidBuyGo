package com.bidbuygo.bid;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.bidbuygo.auth.AuthUser;
import com.bidbuygo.common.error.ApiException;
import com.bidbuygo.common.money.Money;
import com.bidbuygo.listing.Listing;
import com.bidbuygo.listing.ListingService;
import com.bidbuygo.listing.ListingStatus;
import com.bidbuygo.listing.ListingType;
import com.bidbuygo.policy.AuctionPolicyService;
import com.bidbuygo.user.UserService;
import com.bidbuygo.wallet.Wallet;
import com.bidbuygo.wallet.WalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Invariant: for each ACTIVE auction, the only money held for it is currentHighBid, held from highBidderId.
 */
@Service
@RequiredArgsConstructor
public class BidService {

    private final ListingService listingService;
    private final WalletService walletService;
    private final AuctionPolicyService policyService;
    private final UserService userService;
    private final BidRepository bidRepository;
    private final Clock clock;

    /**
     * Follows the money-flow recipe in CLAUDE.md: lock the listing (first read of it in this transaction), validate,
     * lock wallets in ascending id order, release the previous leader's hold, hold the new bid, record it.
     * Validation stops at the first failing rule.
     */
    @Transactional
    public BidResponse placeBid(Long listingId, Long bidderId, long amount) {
        Listing listing = listingService.lockForUpdate(listingId);
        Instant now = clock.instant();

        if (listing.getType() != ListingType.AUCTION) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Not an auction",
                    "Listing " + listingId + " is a fixed-price listing; use buy-now");
        }
        if (listing.getStatus() != ListingStatus.ACTIVE) {
            throw new ApiException(HttpStatus.CONFLICT, "Auction not open",
                    "Listing " + listingId + " is " + listing.getStatus() + " and is not accepting bids");
        }
        if (!listing.getEndsAt().isAfter(now)) {
            throw new ApiException(HttpStatus.CONFLICT, "Auction has ended",
                    "This auction ended at " + listing.getEndsAt());
        }
        if (listing.getSellerId().equals(bidderId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Cannot bid on own listing",
                    "You cannot bid on your own listing");
        }

        Long previousBidder = listing.getHighBidderId();
        Long currentHigh = listing.getCurrentHighBid();
        long minimum = minimumBid(listing, currentHigh);
        if (amount < minimum) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Bid too low", "Bid must be at least " + Money.format(minimum)
                    + (currentHigh == null ? " (the starting price)" : " (current bid plus the minimum increment)"));
        }

        boolean raisingOwnBid = bidderId.equals(previousBidder);
        boolean outbidding = previousBidder != null && !raisingOwnBid;
        Map<Long, Wallet> wallets = walletService.lockWallets(
                outbidding ? List.of(bidderId, previousBidder) : List.of(bidderId));
        Wallet bidderWallet = wallets.get(bidderId);
        // A leader raising their own bid already has currentHigh held; only the difference is new money.
        long toHold = raisingOwnBid ? amount - currentHigh : amount;
        if (bidderWallet.getAvailableBalance() < toHold) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Insufficient funds",
                    "This bid needs %s available but your available balance is %s"
                            .formatted(Money.format(toHold), Money.format(bidderWallet.getAvailableBalance())));
        }

        if (outbidding) {
            walletService.release(wallets.get(previousBidder), currentHigh, listingId);
        }
        walletService.hold(bidderWallet, toHold, listingId);
        Bid bid = bidRepository.save(new Bid(listingId, bidderId, amount, now));
        listing.acceptBid(bidderId, amount);
        return BidResponse.from(bid, userService.get(bidderId).getEmail());
    }

    /** Public bid history, newest first. Hidden (404) wherever the listing itself is hidden. */
    @Transactional(readOnly = true)
    public List<BidResponse> listBids(Long listingId, AuthUser viewer) {
        listingService.findVisible(listingId, viewer);
        List<Bid> bids = bidRepository.findByListingIdOrderByCreatedAtDescIdDesc(listingId);
        Map<Long, String> emails = userService.emailsByIds(bids.stream().map(Bid::getBidderId).distinct().toList());
        return bids.stream().map(bid -> BidResponse.from(bid, emails.get(bid.getBidderId()))).toList();
    }

    private long minimumBid(Listing listing, Long currentHigh) {
        return currentHigh == null
                ? listing.getStartPrice()
                : currentHigh + policyService.get().getMinIncrementPaise();
    }
}
