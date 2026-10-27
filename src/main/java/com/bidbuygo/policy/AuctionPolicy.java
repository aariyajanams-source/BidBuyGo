package com.bidbuygo.policy;

import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.bidbuygo.listing.ConditionGrade;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** The single admin-editable row (id {@value #SINGLETON_ID}) that decides which listings may be auctioned. */
@Entity
@Table(name = "auction_policy")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuctionPolicy {

    public static final long SINGLETON_ID = 1L;

    @Id
    @Setter(AccessLevel.NONE)
    private Long id = SINGLETON_ID;

    /** Stored lower-cased; compare with {@link #allowsCategory}. */
    @Setter(AccessLevel.NONE)
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "auction_policy_categories", joinColumns = @JoinColumn(name = "policy_id"))
    @Column(name = "category", nullable = false, length = 64)
    private Set<String> allowedCategories = new HashSet<>();

    @Setter(AccessLevel.NONE)
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "auction_policy_conditions", joinColumns = @JoinColumn(name = "policy_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "condition_grade", nullable = false, length = 16)
    private Set<ConditionGrade> allowedConditions = new HashSet<>();

    @Column(nullable = false)
    private int minSellerCompletedOrders;

    @Column(nullable = false)
    private boolean requireVerifiedSeller;

    @Column(nullable = false)
    private int minPhotos;

    @Column(nullable = false)
    private long minReservePaise;

    @Column(nullable = false)
    private long minIncrementPaise;

    public static AuctionPolicy defaults() {
        AuctionPolicy policy = new AuctionPolicy();
        policy.replaceAllowedCategories(List.of("collectibles", "pre-owned", "handmade", "limited-edition", "open-box"));
        policy.replaceAllowedConditions(EnumSet.allOf(ConditionGrade.class));
        policy.minSellerCompletedOrders = 5;
        policy.requireVerifiedSeller = true;
        policy.minPhotos = 2;
        policy.minReservePaise = 10_000;
        policy.minIncrementPaise = 1_000;
        return policy;
    }

    // Collections are mutated in place so Hibernate keeps tracking the same managed instances.
    public void replaceAllowedCategories(Collection<String> categories) {
        allowedCategories.clear();
        categories.forEach(category -> allowedCategories.add(category.trim().toLowerCase(Locale.ROOT)));
    }

    public void replaceAllowedConditions(Collection<ConditionGrade> conditions) {
        allowedConditions.clear();
        allowedConditions.addAll(conditions);
    }

    public boolean allowsCategory(String category) {
        return category != null && allowedCategories.contains(category.trim().toLowerCase(Locale.ROOT));
    }

    public List<String> sortedCategories() {
        return allowedCategories.stream().sorted().toList();
    }

    public List<ConditionGrade> sortedConditions() {
        return allowedConditions.stream().sorted().toList();
    }
}
