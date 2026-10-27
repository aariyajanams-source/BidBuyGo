package com.bidbuygo.wallet;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WalletTransactionRepository extends JpaRepository<WalletTransaction, Long> {

    List<WalletTransaction> findByWalletIdOrderByIdAsc(Long walletId);
}
