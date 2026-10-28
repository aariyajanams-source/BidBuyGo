package com.bidbuygo.listing;

import java.util.List;

import com.bidbuygo.auth.AuthUser;
import com.bidbuygo.policy.EligibilityResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/listings")
@RequiredArgsConstructor
public class ListingController {

    private final ListingService listingService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('SELLER')")
    public ListingResponse create(@AuthenticationPrincipal AuthUser principal, @RequestBody ListingDraft draft) {
        return listingService.create(principal.id(), draft);
    }

    @PostMapping("/check")
    @PreAuthorize("hasRole('SELLER')")
    public EligibilityResult check(@AuthenticationPrincipal AuthUser principal, @RequestBody ListingDraft draft) {
        return listingService.check(principal.id(), draft);
    }

    /** Public; principal is null for anonymous callers. */
    @GetMapping
    public List<ListingResponse> list(@RequestParam(required = false) ListingType type,
                                      @RequestParam(required = false) String category,
                                      @AuthenticationPrincipal AuthUser principal) {
        return listingService.listActive(type, category, principal);
    }

    /** Public; principal is null for anonymous callers. */
    @GetMapping("/{id}")
    public ListingResponse get(@PathVariable Long id, @AuthenticationPrincipal AuthUser principal) {
        return listingService.get(id, principal);
    }
}
