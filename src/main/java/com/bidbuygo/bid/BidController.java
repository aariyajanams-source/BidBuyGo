package com.bidbuygo.bid;

import java.util.List;

import com.bidbuygo.auth.AuthUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/listings/{id}/bids")
@RequiredArgsConstructor
public class BidController {

    private final BidService bidService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('BUYER', 'SELLER')")
    public BidResponse place(@PathVariable Long id, @AuthenticationPrincipal AuthUser principal,
                             @Valid @RequestBody PlaceBidRequest request) {
        return bidService.placeBid(id, principal.id(), request.amount());
    }

    /** Public; principal is null for anonymous callers. */
    @GetMapping
    public List<BidResponse> list(@PathVariable Long id, @AuthenticationPrincipal AuthUser principal) {
        return bidService.listBids(id, principal);
    }
}
