package com.bidbuygo.order;

import java.util.List;
import java.util.Map;

import com.bidbuygo.common.error.ApiException;
import com.bidbuygo.common.money.Money;
import com.bidbuygo.listing.Listing;
import com.bidbuygo.listing.ListingService;
import com.bidbuygo.listing.ListingStatus;
import com.bidbuygo.listing.ListingType;
import com.bidbuygo.user.UserService;
import com.bidbuygo.wallet.Wallet;
import com.bidbuygo.wallet.WalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final ListingService listingService;
    private final WalletService walletService;
    private final UserService userService;
    private final OrderRepository orderRepository;

    /**
     * Buy-now in one transaction. Lock order per CLAUDE.md: listing row first, then buyer and seller wallets in
     * ascending wallet id order. Every check runs after the listing lock, so concurrent buyers see the final status.
     */
    @Transactional
    public OrderResponse buyNow(Long listingId, Long buyerId) {
        Listing listing = listingService.lockForUpdate(listingId);
        if (listing.getType() == ListingType.AUCTION) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Not a fixed-price listing",
                    "Listing " + listingId + " is an auction; use the bidding endpoint");
        }
        if (listing.getStatus() != ListingStatus.ACTIVE) {
            String reason = listing.getStatus() == ListingStatus.SOLD
                    ? "Listing " + listingId + " has already been sold"
                    : "Listing " + listingId + " is " + listing.getStatus() + " and cannot be bought";
            throw new ApiException(HttpStatus.CONFLICT, "Listing not available", reason);
        }
        Long sellerId = listing.getSellerId();
        if (sellerId.equals(buyerId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Cannot buy own listing",
                    "You cannot buy your own listing");
        }

        Map<Long, Wallet> wallets = walletService.lockWallets(List.of(buyerId, sellerId));
        Wallet buyerWallet = wallets.get(buyerId);
        long price = listing.getFixedPrice();
        if (buyerWallet.getAvailableBalance() < price) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Insufficient funds",
                    "The price is %s but your available balance is %s"
                            .formatted(Money.format(price), Money.format(buyerWallet.getAvailableBalance())));
        }

        walletService.chargeAvailable(buyerWallet, price, listingId);
        walletService.credit(wallets.get(sellerId), price, listingId);
        Order order = orderRepository.save(new Order(listingId, buyerId, sellerId, price, OrderType.FIXED_PURCHASE));
        listing.markSold();
        userService.incrementCompletedOrders(sellerId);
        return OrderResponse.from(order, listing.getTitle(), buyerId);
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> listMine(Long userId) {
        List<Order> orders = orderRepository.findInvolving(userId);
        Map<Long, String> titles = listingService.titlesById(orders.stream().map(Order::getListingId).toList());
        return orders.stream()
                .map(order -> OrderResponse.from(order, titles.get(order.getListingId()), userId))
                .toList();
    }
}
