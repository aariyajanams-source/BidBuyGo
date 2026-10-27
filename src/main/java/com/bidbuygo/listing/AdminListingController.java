package com.bidbuygo.listing;

import java.util.List;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/listings")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminListingController {

    private final ListingService listingService;

    @GetMapping
    public List<ListingResponse> list(@RequestParam(required = false) ListingStatus status) {
        return listingService.listForAdmin(status);
    }

    @PostMapping("/{id}/reject")
    public ListingResponse reject(@PathVariable Long id, @Valid @RequestBody RejectListingRequest request) {
        return listingService.reject(id, request.reason());
    }
}
