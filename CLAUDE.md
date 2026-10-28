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
- Time: inject `java.time.Clock` (bean in `common.time.ClockConfig`); never call `Instant.now()` in listing/bid logic.
- Tests: annotate Spring tests with `support.IntegrationTest`, never a bare `@SpringBootTest`. A second Spring context
  would re-run create-drop on the shared in-memory H2 database. It also disables the scheduler; settle auctions by
  calling `closeIfDue` or `AuctionCloseJob.runOnce()` after advancing `MutableClock`. Avoid `@MockitoBean`/`@MockitoSpyBean`:
  each one forces a new context. Use unique data (`support.Fixtures`) and restore
  anything global (the auction policy; `MutableClock.reset()` after `advance`) in `@AfterEach`.
  Money tests should finish with `Fixtures.assertLedgerReconstructsEveryWallet` and `assertAuctionHoldsMatchHighBids`.

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
  Both lock helpers use `common.persistence.Locks.ensureWriteLocked`, which re-reads a row that was already loaded
  unlocked in the transaction (a locking query otherwise hands back the stale instance).
- Auction hold invariant: for each ACTIVE auction, the only money held for it is `currentHighBid`, held from
  `highBidderId` (every HOLD/RELEASE row carries the listingId). `BidService.placeBid` releases the previous leader in
  full, or holds only the difference when the leader raises their own bid.
- Anti-sniping (in `BidService.placeBid`, under the listing lock, after the bid is accepted): if `endsAt - now` is at
  most `bidbuygo.auction.snipe-window-seconds` (30), `endsAt += extension-seconds` (30). The response carries the new
  `endsAt` and `extended`. A bid at or after `endsAt` is still rejected as ended.
- Auction settlement:
  - `AuctionCloseScheduler` (`@ConditionalOnProperty bidbuygo.scheduler.enabled`, every `close-poll-ms`) calls
    `AuctionCloseJob.runOnce()`: an unlocked ids-only query for due auctions, then
    `AuctionSettlementService.closeIfDue(id)` per id. Each call is its own transaction; failures are logged and retried
    on the next pass.
  - `closeIfDue`: lock the listing, then re-check ACTIVE AUCTION with `endsAt <= now`, else no-op (idempotent, and
    safe after an extension). Then:
    - reserve met: lock winner + seller wallets, `captureHeld` the winner, `credit` the seller, AUCTION_WIN order,
      SOLD / closeReason SOLD, increment completedOrders;
    - reserve not met: `release` the winner, CLOSED / RESERVE_NOT_MET;
    - no bids: CLOSED / NO_BIDS.
  - Afterwards nothing is held for the listing.
- Seller `completedOrders` is incremented with an atomic UPDATE (`UserService.incrementCompletedOrders`).
- `orders.listing_id` is unique: one sale per listing, enforced by the database.

## Frontend

- `src/main/resources/static/{index.html, app.js, style.css}`: vanilla ES module, hash routing, no build, no CDN.
- Never put server data into HTML: build DOM with `el()` / `textContent`; only `safeHttpUrl` values go into `<img src>`.
  `FrontendResourcesTest` fails if `app.js` contains `innerHTML`, `outerHTML`, `insertAdjacentHTML`, `document.write` or `eval(`.
- Money crosses the UI boundary only through `formatPaise` (display) and `rupeesToPaise` (input; integer maths, no floats).
- Views register timers with `every`/`later`; the router clears them all on navigation.
- The shell files are public via explicit GET matchers in `SecurityConfig`; add any new static file there too.

## Out of scope (roadmap)

WebSockets, Dutch auctions, auto-markdown, shill-bid flags, proxy bidding.
