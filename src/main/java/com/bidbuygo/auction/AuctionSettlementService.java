package com.bidbuygo.auction;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.bidbuygo.listing.CloseReason;
import com.bidbuygo.listing.Listing;
import com.bidbuygo.listing.ListingService;
import com.bidbuygo.listing.ListingStatus;
import com.bidbuygo.listing.ListingType;
import com.bidbuygo.order.OrderService;
import com.bidbuygo.user.UserService;
import com.bidbuygo.wallet.Wallet;
import com.bidbuygo.wallet.WalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Settles ended auctions. A separate bean from {@link AuctionCloseJob} so each call goes through the transactional
 * proxy: every auction settles in its own transaction.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuctionSettlementService {

    private final ListingService listingService;
    private final WalletService walletService;
    private final OrderService orderService;
    private final UserService userService;
    private final Clock clock;

    /**
     * Closes the auction if it is still an ACTIVE auction whose end has passed, otherwise does nothing. That re-check
     * under the listing lock makes this idempotent, and safe against a bid that just extended the auction.
     * Lock order per CLAUDE.md: listing first, then wallets via lockWallets.
     *
     * @return how the auction closed, or empty if nothing was done
     */
    @Transactional
    public Optional<CloseReason> closeIfDue(Long listingId) {
        Listing listing = listingService.lockForUpdate(listingId);
        if (listing.getType() != ListingType.AUCTION
                || listing.getStatus() != ListingStatus.ACTIVE
                || listing.getEndsAt().isAfter(clock.instant())) {
            return Optional.empty();
        }

        Long winnerId = listing.getHighBidderId();
        if (winnerId == null) {
            listing.closeUnsold(CloseReason.NO_BIDS);
            return Optional.of(CloseReason.NO_BIDS);
        }

        long winningBid = listing.getCurrentHighBid();
        Long sellerId = listing.getSellerId();
        if (winningBid >= listing.getReservePrice()) {
            Map<Long, Wallet> wallets = walletService.lockWallets(List.of(winnerId, sellerId));
            walletService.captureHeld(wallets.get(winnerId), winningBid, listingId);
            walletService.credit(wallets.get(sellerId), winningBid, listingId);
            orderService.recordAuctionWin(listingId, winnerId, sellerId, winningBid);
            listing.markSold();
            userService.incrementCompletedOrders(sellerId);
            log.info("Auction {} sold to user {} for {} paise", listingId, winnerId, winningBid);
            return Optional.of(CloseReason.SOLD);
        }

        Wallet winnerWallet = walletService.lockWallets(List.of(winnerId)).get(winnerId);
        walletService.release(winnerWallet, winningBid, listingId);
        listing.closeUnsold(CloseReason.RESERVE_NOT_MET);
        return Optional.of(CloseReason.RESERVE_NOT_MET);
    }
}
