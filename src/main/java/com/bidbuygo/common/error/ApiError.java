package com.bidbuygo.common.error;

import java.util.List;

public record ApiError(String message, List<String> reasons) {
}
