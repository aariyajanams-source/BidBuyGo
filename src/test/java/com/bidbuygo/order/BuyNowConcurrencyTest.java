package com.bidbuygo.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.bidbuygo.common.error.ApiException;
import com.bidbuygo.listing.ListingRepository;
import com.bidbuygo.listing.ListingService;
import com.bidbuygo.listing.ListingStatus;
import com.bidbuygo.support.Fixtures;
import com.bidbuygo.user.Role;
import com.bidbuygo.user.User;
import com.bidbuygo.user.UserRepository;
import com.bidbuygo.user.UserService;
import com.bidbuygo.wallet.Wallet;
import com.bidbuygo.wallet.WalletRepository;
import com.bidbuygo.wallet.WalletService;
import com.bidbuygo.wallet.WalletTransaction;
import com.bidbuygo.wallet.WalletTransactionRepository;
import com.bidbuygo.wallet.WalletTransactionType;
import com.bidbuygo.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class BuyNowConcurrencyTest {

    private static final int CREATED = 201;

    @Autowired OrderService orderService;
    @Autowired UserService userService;
    @Autowired UserRepository userRepository;
    @Autowired WalletService walletService;
    @Autowired WalletRepository walletRepository;
    @Autowired WalletTransactionRepository transactionRepository;
    @Autowired ListingService listingService;
    @Autowired ListingRepository listingRepository;
    @Autowired OrderRepository orderRepository;

    @Test
    void tenBuyersRaceForOneListingAndExactlyOneWins() throws Exception {
        long price = 25_000;
        User seller = Fixtures.user(userService, Role.SELLER);
        long listingId = Fixtures.fixedListing(listingService, seller.getId(), price);
        List<User> buyers = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            buyers.add(Fixtures.fundedUser(userService, walletService, Role.BUYER, 50_000));
        }
        long moneyBefore = Fixtures.totalMoney(walletRepository);

        List<Integer> statuses = race(buyers.stream()
                .map(buyer -> (Callable<Integer>) () -> buy(listingId, buyer.getId()))
                .toList());

        assertThat(statuses).filteredOn(s -> s == CREATED).hasSize(1);
        assertThat(statuses).filteredOn(s -> s == 409).hasSize(9);

        Wallet sellerWallet = walletRepository.findByUserId(seller.getId()).orElseThrow();
        assertThat(sellerWallet.getAvailableBalance()).isEqualTo(price);
        assertThat(transactionRepository.findByWalletIdOrderByIdAsc(sellerWallet.getId()))
                .extracting(WalletTransaction::getType).containsExactly(WalletTransactionType.CREDIT);
        assertThat(userRepository.findById(seller.getId()).orElseThrow().getCompletedOrders()).isEqualTo(1);
        assertThat(listingRepository.findById(listingId).orElseThrow().getStatus()).isEqualTo(ListingStatus.SOLD);

        long charged = buyers.stream()
                .map(b -> walletRepository.findByUserId(b.getId()).orElseThrow())
                .filter(w -> w.getAvailableBalance() == 50_000 - price)
                .count();
        assertThat(charged).isEqualTo(1);
        Order order = orderRepository.findByListingId(listingId).orElseThrow();
        assertThat(walletRepository.findByUserId(order.getBuyerId()).orElseThrow().getAvailableBalance())
                .isEqualTo(50_000 - price);

        assertThat(Fixtures.totalMoney(walletRepository)).isEqualTo(moneyBefore);
        Fixtures.assertLedgerReconstructsEveryWallet(walletRepository, transactionRepository);
    }

    @Test
    void oneBuyerBuyingTwoListingsAtOnceCanOnlyAffordOne() throws Exception {
        long price = 30_000;
        User buyer = Fixtures.fundedUser(userService, walletService, Role.BUYER, 40_000);
        long first = Fixtures.fixedListing(listingService, Fixtures.user(userService, Role.SELLER).getId(), price);
        long second = Fixtures.fixedListing(listingService, Fixtures.user(userService, Role.SELLER).getId(), price);
        long moneyBefore = Fixtures.totalMoney(walletRepository);

        List<Integer> statuses = race(List.of(
                () -> buy(first, buyer.getId()),
                () -> buy(second, buyer.getId())));

        assertThat(statuses).containsExactlyInAnyOrder(CREATED, 400);
        Wallet wallet = walletRepository.findByUserId(buyer.getId()).orElseThrow();
        assertThat(wallet.getAvailableBalance()).isEqualTo(10_000).isNotNegative();
        assertThat(wallet.getHeldBalance()).isZero();
        assertThat(transactionRepository.findByWalletIdOrderByIdAsc(wallet.getId()))
                .extracting(WalletTransaction::getType)
                .containsExactly(WalletTransactionType.DEPOSIT, WalletTransactionType.CAPTURE);
        assertThat(List.of(first, second))
                .filteredOn(id -> listingRepository.findById(id).orElseThrow().getStatus() == ListingStatus.SOLD)
                .hasSize(1);

        assertThat(Fixtures.totalMoney(walletRepository)).isEqualTo(moneyBefore);
        Fixtures.assertLedgerReconstructsEveryWallet(walletRepository, transactionRepository);
    }

    private int buy(long listingId, Long buyerId) {
        try {
            orderService.buyNow(listingId, buyerId);
            return CREATED;
        } catch (ApiException e) {
            return e.getStatus().value();
        }
    }

    /** Starts every task at the same instant (all threads wait on one latch) and returns their results. */
    private static List<Integer> race(List<Callable<Integer>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<Integer> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return task.call();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Integer> results = new ArrayList<>();
            for (Future<Integer> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
