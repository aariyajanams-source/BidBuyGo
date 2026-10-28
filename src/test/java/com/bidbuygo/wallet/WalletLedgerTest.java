package com.bidbuygo.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;

import com.bidbuygo.common.error.ApiException;
import com.bidbuygo.support.Fixtures;
import com.bidbuygo.user.Role;
import com.bidbuygo.user.User;
import com.bidbuygo.user.UserService;
import com.bidbuygo.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
class WalletLedgerTest {

    @Autowired WalletService walletService;
    @Autowired WalletRepository walletRepository;
    @Autowired WalletTransactionRepository transactionRepository;
    @Autowired UserService userService;
    @Autowired TransactionTemplate tx;

    private User user;

    @BeforeEach
    void setUp() {
        user = Fixtures.fundedUser(userService, walletService, Role.BUYER, 10_000);
    }

    @Test
    void eachOperationMovesTheRightBalanceAndWritesOneRow() {
        inTx(w -> walletService.hold(w, 4_000, 1L));
        assertBalances(6_000, 4_000);
        inTx(w -> walletService.release(w, 1_000, 1L));
        assertBalances(7_000, 3_000);
        inTx(w -> walletService.captureHeld(w, 3_000, 1L));
        assertBalances(7_000, 0);
        inTx(w -> walletService.chargeAvailable(w, 2_000, 2L));
        assertBalances(5_000, 0);
        inTx(w -> walletService.credit(w, 500, 3L));
        assertBalances(5_500, 0);

        assertThat(rows()).extracting(WalletTransaction::getType, WalletTransaction::getAmount,
                        WalletTransaction::getAvailableDelta, WalletTransaction::getHeldDelta, WalletTransaction::getListingId)
                .containsExactly(
                        tuple(WalletTransactionType.DEPOSIT, 10_000L, 10_000L, 0L, null),
                        tuple(WalletTransactionType.HOLD, 4_000L, -4_000L, 4_000L, 1L),
                        tuple(WalletTransactionType.RELEASE, 1_000L, 1_000L, -1_000L, 1L),
                        tuple(WalletTransactionType.CAPTURE, 3_000L, 0L, -3_000L, 1L),
                        tuple(WalletTransactionType.CAPTURE, 2_000L, -2_000L, 0L, 2L),
                        tuple(WalletTransactionType.CREDIT, 500L, 500L, 0L, 3L));
        Fixtures.assertLedgerReconstructsEveryWallet(walletRepository, transactionRepository);
    }

    @Test
    void holdBeyondAvailableIsRefusedAndRolledBack() {
        assertRefused(w -> walletService.hold(w, 10_001, 1L));
    }

    @Test
    void chargeBeyondAvailableIsRefusedAndRolledBack() {
        assertRefused(w -> walletService.chargeAvailable(w, 10_001, 1L));
    }

    @Test
    void releaseBeyondHeldIsRefusedAndRolledBack() {
        inTx(w -> walletService.hold(w, 1_000, 1L));
        int rowsBefore = rows().size();

        assertThatThrownBy(() -> inTx(w -> walletService.release(w, 1_001, 1L)))
                .isInstanceOf(InsufficientFundsException.class)
                .hasMessage("Insufficient funds");
        assertBalances(9_000, 1_000);
        assertThat(rows()).hasSize(rowsBefore);
    }

    @Test
    void captureBeyondHeldIsRefused() {
        assertThatThrownBy(() -> inTx(w -> walletService.captureHeld(w, 1, 1L)))
                .isInstanceOf(InsufficientFundsException.class);
        assertBalances(10_000, 0);
    }

    @Test
    void operationsRequireAnExistingTransaction() {
        Wallet wallet = walletRepository.findByUserId(user.getId()).orElseThrow();

        assertThatThrownBy(() -> walletService.credit(wallet, 100, 1L))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> walletService.lockWallets(List.of(user.getId())))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertBalances(10_000, 0);
    }

    @Test
    void operationsRequireTheWalletToBeLocked() {
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            Wallet unlocked = walletRepository.findByUserId(user.getId()).orElseThrow();
            walletService.credit(unlocked, 100, 1L);
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("must be locked");
        assertBalances(10_000, 0);
    }

    @Test
    void lockWalletsReturnsEveryRequestedWalletKeyedByUser() {
        User other = Fixtures.user(userService, Role.SELLER);

        tx.executeWithoutResult(status -> {
            var wallets = walletService.lockWallets(List.of(other.getId(), user.getId(), user.getId()));
            assertThat(wallets).containsOnlyKeys(user.getId(), other.getId());
            assertThat(wallets.get(user.getId()).getUserId()).isEqualTo(user.getId());
        });
    }

    @Test
    void lockWalletsLocksAWalletAlreadyLoadedInTheTransaction() {
        tx.executeWithoutResult(status -> {
            Wallet loadedEarly = walletRepository.findByUserId(user.getId()).orElseThrow();
            Wallet locked = walletService.lockWallets(List.of(user.getId())).get(user.getId());
            assertThat(locked).isSameAs(loadedEarly);
            walletService.credit(locked, 100, 1L);
        });
        assertBalances(10_100, 0);
    }

    @Test
    void lockWalletsRejectsUnknownUser() {
        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                walletService.lockWallets(List.of(user.getId(), Long.MAX_VALUE))))
                .isInstanceOf(ApiException.class)
                .hasMessage("Wallet not found");
    }

    private void assertRefused(java.util.function.Consumer<Wallet> operation) {
        int rowsBefore = rows().size();
        assertThatThrownBy(() -> inTx(operation))
                .isInstanceOf(InsufficientFundsException.class)
                .satisfies(e -> assertThat(((InsufficientFundsException) e).getReasons().get(0))
                        .contains("has ₹100.00 available; ₹100.01 required"));
        assertBalances(10_000, 0);
        assertThat(rows()).hasSize(rowsBefore);
    }

    private void inTx(java.util.function.Consumer<Wallet> operation) {
        tx.executeWithoutResult(status -> operation.accept(walletService.lockWallets(List.of(user.getId())).get(user.getId())));
    }

    private void assertBalances(long available, long held) {
        Wallet wallet = walletRepository.findByUserId(user.getId()).orElseThrow();
        assertThat(wallet.getAvailableBalance()).as("available").isEqualTo(available);
        assertThat(wallet.getHeldBalance()).as("held").isEqualTo(held);
    }

    private List<WalletTransaction> rows() {
        Wallet wallet = walletRepository.findByUserId(user.getId()).orElseThrow();
        return transactionRepository.findByWalletIdOrderByIdAsc(wallet.getId());
    }
}
