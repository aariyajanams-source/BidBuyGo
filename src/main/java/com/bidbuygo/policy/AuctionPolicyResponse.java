package com.bidbuygo.policy;

import java.util.List;

import com.bidbuygo.listing.ConditionGrade;

public record AuctionPolicyResponse(
        List<String> allowedCategories,
        List<ConditionGrade> allowedConditions,
        int minSellerCompletedOrders,
        boolean requireVerifiedSeller,
        int minPhotos,
        long minReservePaise,
        long minIncrementPaise) {

    static AuctionPolicyResponse from(AuctionPolicy p) {
        return new AuctionPolicyResponse(p.sortedCategories(), p.sortedConditions(), p.getMinSellerCompletedOrders(),
                p.isRequireVerifiedSeller(), p.getMinPhotos(), p.getMinReservePaise(), p.getMinIncrementPaise());
    }
}
