# BidBuyGo

Spring Boot 3.5 / Java 21 / Maven. Base package `com.bidbuygo`.
Build & test: `./mvnw test` from Git Bash. Maven is not installed globally; always use the wrapper.
Don't use `mvnw.cmd`: it fails because the apostrophe in the project path ("AARIYA's WORKLOG") breaks its PowerShell call.
`JAVA_HOME` must point to a JDK directory (e.g. `/c/Program Files/Java/jdk-24`).
Default profile is `dev` (in-memory H2, H2 console, idempotent seed data in `dev.DevDataSeeder`, password `password123`).
`application-postgres.yml` is used with `SPRING_PROFILES_ACTIVE=postgres` (no console, no seed).
If tests fail with `ClassFormatError ... Code length 0`, the IDE's compiler clobbered `target/classes`; run `./mvnw clean test`.

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
- Every rule rejection returns a list of human-readable reasons: throw `common.error.ApiException(status, message, reasons)`;
  `GlobalExceptionHandler` renders `{message, reasons}`. Security 401/403 use the same shape.
- Current user in controllers: `@AuthenticationPrincipal AuthUser` (id, email, role from the JWT).
  For rules that depend on seller trust (verified, completedOrders), reload the `User`; the token can be stale.
- Status codes: 400 malformed/invalid input, 422 valid input that a business rule rejects (e.g. auction eligibility),
  409 state conflicts (duplicate email, wrong listing status).
- Auction eligibility lives in `policy.EligibilityService.evaluate`; it collects every failing rule, never stops early.
  The policy is a single row (id 1) seeded in all profiles by `AuctionPolicySeeder`. Seeders are ordered
  ApplicationRunners: policy `@Order(0)`, dev data `@Order(10)`.
- Integration tests share one Spring context and H2 database: use unique data, and restore anything global
  (e.g. the auction policy) in `@AfterEach`.

## Concurrency

- In any flow touching money or bids, lock the listing row first (`PESSIMISTIC_WRITE`),
  then lock wallets in ascending wallet id order to avoid deadlocks.
- Wallet invariant: `available` + `held` change only via ledger entries.
  In code: `Wallet.applyDelta` is package-private and only `WalletService.record` calls it, together with saving
  the `WalletTransaction` (signed `availableDelta`/`heldDelta`, so replaying a wallet's rows from zero gives its balances).
- Money flow recipe, all in one `@Transactional` service method:
  1. `listingService.lockForUpdate(id)`, then re-check status under the lock.
  2. `walletService.lockWallets(userIds)`: locks in ascending wallet id order. Never lock wallets any other way.
  3. `hold` / `release` / `captureHeld` / `chargeAvailable` / `credit` (propagation MANDATORY; they refuse
     unlocked wallets and negative balances).
- Seller `completedOrders` is incremented with an atomic UPDATE (`UserService.incrementCompletedOrders`).
- `orders.listing_id` is unique: one sale per listing, enforced by the database.

## Out of scope (roadmap)

WebSockets, Dutch auctions, auto-markdown, shill-bid flags, proxy bidding.
