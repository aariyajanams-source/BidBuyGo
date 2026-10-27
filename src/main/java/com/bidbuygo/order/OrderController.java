package com.bidbuygo.order;

import java.util.List;

import com.bidbuygo.auth.AuthUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping("/listings/{id}/buy")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('BUYER', 'SELLER')")
    public OrderResponse buy(@PathVariable Long id, @AuthenticationPrincipal AuthUser principal) {
        return orderService.buyNow(id, principal.id());
    }

    @GetMapping("/orders/mine")
    public List<OrderResponse> mine(@AuthenticationPrincipal AuthUser principal) {
        return orderService.listMine(principal.id());
    }
}
