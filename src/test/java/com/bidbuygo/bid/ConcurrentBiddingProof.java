package com.bidbuygo.bid;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.bidbuygo.auction.AuctionSettlementService;
import com.bidbuygo.common.error.ApiException;
import com.bidbuygo.listing.CloseReason;
import com.bidbuygo.listing.Listing;
import com.bidbuygo.listing.ListingRepository;
import com.bidbuygo.listing.ListingService;
import com.bidbuygo.order.Order;
import com.bidbuygo.order.OrderRepository;
import com.bidbuygo.order.OrderType;
import com.bidbuygo.support.Fixtures;
import com.bidbuygo.support.MutableClock;
import com.bidbuygo.user.Role;
import com.bidbuygo.user.User;
import com.bidbuygo.user.UserRepository;
import com.bidbuygo.user.UserService;
import com.bidbuygo.wallet.Wallet;
import com.bidbuygo.wallet.WalletRepository;
import com.bidbuygo.wallet.WalletService;
import com.bidbuygo.wallet.WalletTransactionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Proof that the bid flow keeps every money invariant under a burst of simultaneous bids, then settles cleanly.
 * Subclasses pick the database: {@link ConcurrentBiddingProofTest} (H2, test phase) and
 * {@link ConcurrentBiddingProofPostgresIT} (Postgres via Testcontainers, verify phase).
 */
abstract class ConcurrentBiddingProof {

    private static final int RUNS = 3;
    private static final int BUYERS = 20;
    private static final int BIDS = 200;
    // Fixtures.auctionListing: start ₹200.00, reserve ₹500.00. Amounts are ₹200..₹800 in ₹10 steps, so many collide.
    private static final long START = 20_000;
    private static final long RESERVE = 50_000;
    private static final long STEP = 1_000;
    private static final int STEPS = 60;
    // Covers the largest possible bid, so no bid is rejected for lack of funds.
    private static final long FUNDING = START + STEPS * STEP;

    @Autowired BidService bidService;
    @Autowired BidRepository bidRepository;
    @Autowired UserService userService;
    @Autowired UserRepository userRepository;
    @Autowired WalletService walletService;
    @Autowired WalletRepository walletRepository;
    @Autowired WalletTransactionRepository transactionRepository;
    @Autowired ListingService listingService;
    @Autowired ListingRepository listingRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired AuctionSettlementService settlementService;
    @Autowired MutableClock clock;

    /** Label for the summary lines. */
    abstract String database();

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void twoHundredSimultaneousBidsKeepEveryInvariantThreeRunsInARow() throws Exception {
        for (int run = 1; run <= RUNS; run++) {
            runScenario(run, new Random());
            clock.reset();
        }
    }

    private void runScenario(int run, Random random) throws Exception {
        User seller = Fixtures.trustedSeller(userService, userRepository);
        long auctionId = Fixtures.auctionListing(listingService, seller.getId(),
                clock.instant().plus(1, ChronoUnit.DAYS));
        List<User> buyers = new ArrayList<>();
        for (int i = 0; i < BUYERS; i++) {
            buyers.add(Fixtures.fundedUser(userService, walletService, Role.BUYER, FUNDING));
        }
        List<Long> participants = new ArrayList<>(buyers.stream().map(User::getId).toList());
        participants.add(seller.getId());
        long moneyBefore = Fixtures.totalMoney(walletRepository);

        Outcome outcome = fireSimultaneousBids(auctionId, buyers, random);

        // g) every request came back as accepted or rejected: none lost, none hung, nothing unexpected.
        assertThat(outcome.unexpected()).as("unexpected failures").isEmpty();
        assertThat(outcome.accepted() + outcome.rejected()).isEqualTo(BIDS);

        List<Bid> accepted = bidRepository.findByListingIdOrderByCreatedAtDescIdDesc(auctionId).stream()
                .sorted(Comparator.comparing(Bid::getId))
                .toList();
        assertThat(accepted).hasSize(outcome.accepted());

        // a) each accepted bid is strictly higher than the one accepted before it.
        for (int i = 1; i < accepted.size(); i++) {
            assertThat(accepted.get(i).getAmountPaise())
                    .as("bid %d vs bid %d", accepted.get(i).getId(), accepted.get(i - 1).getId())
                    .isGreaterThan(accepted.get(i - 1).getAmountPaise());
        }

        // b) the listing points at the highest accepted bid and its bidder.
        Bid highest = accepted.getLast();
        Listing listing = listingRepository.findById(auctionId).orElseThrow();
        assertThat(listing.getCurrentHighBid()).isEqualTo(highest.getAmountPaise());
        assertThat(listing.getHighBidderId()).isEqualTo(highest.getBidderId());
        assertThat(listing.getBidCount()).isEqualTo(accepted.size());

        // c) the only money held for the listing is currentHighBid, held by the leader; nobody else holds anything.
        assertThat(Fixtures.heldForListing(transactionRepository, auctionId)).isEqualTo(highest.getAmountPaise());
        Map<Long, Long> heldByUser = heldByUser(participants);
        heldByUser.forEach((userId, held) -> assertThat(held).as("held by user %d", userId)
                .isEqualTo(userId.equals(highest.getBidderId()) ? highest.getAmountPaise() : 0L));

        assertGlobalInvariants(moneyBefore); // d), e), f)

        // Settle: advance past endsAt and close.
        clock.advance(Duration.ofDays(2));
        boolean reserveMet = highest.getAmountPaise() >= RESERVE;
        assertThat(settlementService.closeIfDue(auctionId))
                .contains(reserveMet ? CloseReason.SOLD : CloseReason.RESERVE_NOT_MET);

        List<Order> wins = orderRepository.findByListingId(auctionId).stream()
                .filter(o -> o.getType() == OrderType.AUCTION_WIN)
                .toList();
        assertThat(wins).hasSize(reserveMet ? 1 : 0);
        if (reserveMet) {
            assertThat(wins.getFirst().getBuyerId()).isEqualTo(highest.getBidderId());
            assertThat(wins.getFirst().getAmountPaise()).isEqualTo(highest.getAmountPaise());
        }
        assertThat(Fixtures.heldForListing(transactionRepository, auctionId)).isZero();
        heldByUser(participants).forEach((userId, held) -> assertThat(held).as("held by user %d after settlement", userId)
                .isZero());
        assertGlobalInvariants(moneyBefore);

        System.out.printf("[ConcurrentBiddingProof] db=%s run=%d bids=%d accepted=%d rejected=%d winner=user#%d "
                        + "finalBid=%d paise reserveMet=%s close=%s%n",
                database(), run, BIDS, outcome.accepted(), outcome.rejected(), highest.getBidderId(),
                highest.getAmountPaise(), reserveMet, reserveMet ? CloseReason.SOLD : CloseReason.RESERVE_NOT_MET);
    }

    /** All bids wait on one latch, then go at once. Each future resolves to accepted, rejected or unexpected. */
    private Outcome fireSimultaneousBids(long auctionId, List<User> buyers, Random random) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(BIDS);
        CountDownLatch ready = new CountDownLatch(BIDS);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Result>> futures = new ArrayList<>();
            for (int i = 0; i < BIDS; i++) {
                Long bidderId = buyers.get(i % BUYERS).getId();
                long amount = START + random.nextInt(STEPS + 1) * STEP;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return place(auctionId, bidderId, amount);
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).as("all bid threads ready").isTrue();
            go.countDown();

            int accepted = 0;
            int rejected = 0;
            List<String> unexpected = new ArrayList<>();
            for (Future<Result> future : futures) {
                Result result = future.get(60, TimeUnit.SECONDS); // a hang fails the test here
                switch (result.kind()) {
                    case ACCEPTED -> accepted++;
                    case REJECTED -> rejected++;
                    case UNEXPECTED -> unexpected.add(result.detail());
                }
            }
            return new Outcome(accepted, rejected, unexpected);
        } finally {
            pool.shutdownNow();
        }
    }

    private Result place(long auctionId, Long bidderId, long amount) {
        try {
            bidService.placeBid(auctionId, bidderId, amount);
            return new Result(Kind.ACCEPTED, null);
        } catch (ApiException e) {
            // Expected losers: "Bid too low" once someone else got there first. Anything else is a bug.
            return "Bid too low".equals(e.getMessage())
                    ? new Result(Kind.REJECTED, null)
                    : new Result(Kind.UNEXPECTED, e.getMessage() + " " + e.getReasons());
        } catch (DataIntegrityViolationException e) {
            // The (listingId, amountPaise) unique constraint; the listing lock should make it unreachable.
            return new Result(Kind.UNEXPECTED, "unique constraint hit: " + e.getMostSpecificCause().getMessage());
        } catch (RuntimeException e) {
            return new Result(Kind.UNEXPECTED, e.toString());
        }
    }

    /** d) no negative balance, e) total money conserved, f) every wallet's ledger replays to its balances. */
    private void assertGlobalInvariants(long moneyBefore) {
        for (Wallet wallet : walletRepository.findAll()) {
            assertThat(wallet.getAvailableBalance()).as("available of wallet %d", wallet.getId()).isNotNegative();
            assertThat(wallet.getHeldBalance()).as("held of wallet %d", wallet.getId()).isNotNegative();
        }
        assertThat(Fixtures.totalMoney(walletRepository)).as("total money").isEqualTo(moneyBefore);
        Fixtures.assertLedgerReconstructsEveryWallet(walletRepository, transactionRepository);
        Fixtures.assertAuctionHoldsMatchHighBids(listingRepository, walletRepository, transactionRepository);
    }

    private Map<Long, Long> heldByUser(List<Long> userIds) {
        return walletRepository.findAll().stream()
                .filter(w -> userIds.contains(w.getUserId()))
                .collect(Collectors.toMap(Wallet::getUserId, Wallet::getHeldBalance));
    }

    private enum Kind { ACCEPTED, REJECTED, UNEXPECTED }

    private record Result(Kind kind, String detail) {
    }

    private record Outcome(int accepted, int rejected, List<String> unexpected) {
    }
}
