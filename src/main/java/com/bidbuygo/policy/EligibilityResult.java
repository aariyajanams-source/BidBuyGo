package com.bidbuygo.policy;

import java.util.List;

public record EligibilityResult(boolean eligible, List<String> reasons) {

    public static EligibilityResult of(List<String> reasons) {
        return new EligibilityResult(reasons.isEmpty(), List.copyOf(reasons));
    }
}
