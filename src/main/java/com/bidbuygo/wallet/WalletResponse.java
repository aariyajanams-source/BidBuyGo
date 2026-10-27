package com.bidbuygo.wallet;

public record WalletResponse(Long walletId, long availableBalance, long heldBalance) {

    static WalletResponse from(Wallet wallet) {
        return new WalletResponse(wallet.getId(), wallet.getAvailableBalance(), wallet.getHeldBalance());
    }
}
