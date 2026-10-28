package com.bidbuygo.bid;

import com.bidbuygo.support.IntegrationTest;

/** The concurrency proof on in-memory H2, in the normal test phase. */
@IntegrationTest
class ConcurrentBiddingProofTest extends ConcurrentBiddingProof {

    @Override
    String database() {
        return "H2";
    }
}
