package com.bidbuygo.order;

import java.time.Instant;

/** role is the viewer's side of the order: "BUYER" or "SELLER". */
public record OrderResponse(
        Long id,
        Long listingId,
        String listingTitle,
        Long buyerId,
        Long sellerId,
        long amountPaise,
        OrderType type,
        Instant createdAt,
        String role) {

    static OrderResponse from(Order order, String listingTitle, Long viewerId) {
        return new OrderResponse(order.getId(), order.getListingId(), listingTitle, order.getBuyerId(),
                order.getSellerId(), order.getAmountPaise(), order.getType(), order.getCreatedAt(),
                order.getBuyerId().equals(viewerId) ? "BUYER" : "SELLER");
    }
}
