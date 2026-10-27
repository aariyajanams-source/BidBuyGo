package com.bidbuygo.user;

public record UserResponse(Long id, String email, Role role, boolean verified, int completedOrders) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getRole(), user.isVerified(), user.getCompletedOrders());
    }
}
