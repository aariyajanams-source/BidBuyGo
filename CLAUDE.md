# BidBuyGo

Spring Boot 3.5 / Java 21 / Maven. Base package `com.bidbuygo`.
Build & test: `./mvnw test` from Git Bash. Maven is not installed globally; always use the wrapper.
Don't use `mvnw.cmd`: it fails because the apostrophe in the project path ("AARIYA's WORKLOG") breaks its PowerShell call.
`JAVA_HOME` must point to a JDK directory (e.g. `/c/Program Files/Java/jdk-24`).
Default DB is in-memory H2; `application-postgres.yml` is used with `SPRING_PROFILES_ACTIVE=postgres`.

## Product

BidBuyGo is a hybrid marketplace. Sellers list items at a fixed price (Myntra-style)
or as an auction, but only eligible sellers/items may be auctioned. Buyers buy or bid.
Admins set the auction eligibility policy and review listings.

## Roles

`BUYER`, `SELLER`, `ADMIN`.

## Conventions

- Package-by-feature (e.g. `com.bidbuygo.listing`, `com.bidbuygo.wallet`), not by layer.
- Thin controllers; business rules live in services.
- DTOs are Java records.
- Money is a `long` in paise. Never use floating point for money.
- Every rule rejection returns a list of human-readable reasons.

## Concurrency

- In any flow touching money or bids, lock the listing row first (`PESSIMISTIC_WRITE`),
  then lock wallets in ascending wallet id order to avoid deadlocks.
- Wallet invariant: `available` + `held` change only via ledger entries.

## Out of scope (roadmap)

WebSockets, Dutch auctions, auto-markdown, shill-bid flags, proxy bidding.
