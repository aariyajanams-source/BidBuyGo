package com.bidbuygo.bid;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BidResponseTest {

    @ParameterizedTest
    @CsvSource({
            "buyer1@bidbuygo.dev, b***1@bidbuygo.dev",
            "bidder@gmail.com, b***r@gmail.com",
            "ab@x.com, a***@x.com",
            "a@x.com, a***@x.com",
            "no-at-sign, n***n",
    })
    void masksEmailLocalPart(String email, String expected) {
        assertThat(BidResponse.maskEmail(email)).isEqualTo(expected);
    }
}
