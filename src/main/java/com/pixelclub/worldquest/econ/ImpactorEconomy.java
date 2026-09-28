package com.pixelclub.worldquest.econ;

import net.impactdev.impactor.api.economy.EconomyService;
import net.impactdev.impactor.api.economy.accounts.Account;
import net.impactdev.impactor.api.economy.currency.Currency;
import net.impactdev.impactor.api.economy.transactions.EconomyTransaction;
import org.slf4j.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Withdraw-only bridge to the server's real money, ported from coinflip-fabric's adapter (itself from
 * pixelclub-tournament), keeping the rules each learned from an incident:
 * <ol>
 *   <li>Never cache the {@code Account} — Impactor evicts idle accounts and a stale instance writes back an old
 *       balance.</li>
 *   <li>Read the balance back: the transaction object is the <i>executed</i> rung, the read-back is <i>state</i>.</li>
 *   <li>Compare the read-back account by identity (stale-instance hazard).</li>
 *   <li>Never retry without re-reading: Impactor writes before the Post event that can throw, so "it threw" often
 *       means "it worked". A retry blind is a double charge.</li>
 * </ol>
 * Constructed only after {@link #probe} confirms the classes exist, so a server without Impactor gets a mod that
 * refuses donations with a reason instead of a NoClassDefFoundError.
 */
public final class ImpactorEconomy implements Econ {

    private static final long FETCH_TIMEOUT_SECONDS = 2;
    private static final int MAX_ATTEMPTS = 3;

    private final int decimals;
    private final Logger log;

    private ImpactorEconomy(int decimals, Logger log) {
        this.decimals = decimals;
        this.log = log;
    }

    public static Econ probe(Logger log) {
        try {
            EconomyService service = EconomyService.instance();
            Currency currency = service.currencies().primary();
            int decimals = currency.decimals();
            log.info("[WorldQuest] economy: Impactor present ({}), primary currency {} with {} decimal(s)",
                    service.getClass().getName(), currency.key().asString(), decimals);
            return new ImpactorEconomy(decimals, log);
        } catch (Throwable t) {
            log.error("[WorldQuest] no Impactor economy — money donations are refused ({})", String.valueOf(t));
            return new NoEconomy(String.valueOf(t));
        }
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public String describe() {
        return "Impactor (primary currency, " + decimals + "dp)";
    }

    @Override
    public int decimals() {
        return decimals;
    }

    @Override
    public BigDecimal balance(UUID owner) {
        Account account = account(owner);
        return account == null ? null : read(account, owner);
    }

    @Override
    public Move withdraw(UUID owner, BigDecimal amount, String reason) {
        if (owner == null) return Move.fail("null owner");
        if (amount == null || amount.signum() <= 0) return Move.fail("amount must be > 0 (got " + amount + ")");
        BigDecimal delta = round(amount);

        String lastError = "unknown";
        String lastResult = "NONE";
        BigDecimal startedAt = null;
        BigDecimal latest = null;
        int attempt = 0;
        boolean threw = false;

        while (attempt < MAX_ATTEMPTS) {
            attempt++;
            Account account = account(owner);
            if (account == null) {
                lastError = "account lookup failed";
                continue;
            }
            BigDecimal now = read(account, owner);
            if (now == null) {
                lastError = "balance read failed";
                continue;
            }
            latest = now;
            if (startedAt == null) {
                startedAt = now;
            } else if (equal(now, startedAt.subtract(delta))) {
                if (threw) {
                    log.error("[WorldQuest] withdraw {} from {} threw, and the balance moved {} -> {} in memory — "
                                    + "not known to be saved; recorded UNDECIDED. reason={}",
                            delta.toPlainString(), owner, startedAt.toPlainString(), now.toPlainString(), reason);
                    return new Move(false, startedAt, now, false,
                            "the provider threw after the balance moved; not known to be saved", "UNDECIDED", attempt);
                }
                log.warn("[WorldQuest] withdraw {} from {} looked like it failed, but the balance already moved "
                                + "{} -> {} — not repeating it. reason={}", delta.toPlainString(), owner,
                        startedAt.toPlainString(), now.toPlainString(), reason);
                return new Move(true, startedAt, now, true, null, "ALREADY_APPLIED", attempt);
            }

            BigDecimal before = now;
            EconomyTransaction transaction;
            try {
                transaction = account.withdraw(delta);
            } catch (Throwable t) {
                threw = true;
                lastError = "provider threw " + t;
                log.warn("[WorldQuest] withdraw {} from {} threw on attempt {}: {}", delta.toPlainString(), owner,
                        attempt, String.valueOf(t));
                continue;
            }

            boolean ok;
            String result;
            try {
                ok = transaction.successful();
                result = transaction.result() == null ? "?" : transaction.result().toString();
            } catch (Throwable t) {
                lastError = "could not read the transaction result: " + t;
                continue;
            }

            BigDecimal want = round(before.subtract(delta));
            Account again = account(owner);
            boolean staleInstance = again != null && again != account;
            BigDecimal recheck = again == null ? null : read(again, owner);
            if (recheck != null) latest = recheck;
            if (staleInstance) {
                log.error("[WorldQuest] STALE ACCOUNT INSTANCE for {} — two live Account objects for one uuid. "
                        + "amount={} reason={}", owner, delta.toPlainString(), reason);
            }

            if (ok) {
                boolean verified = !staleInstance && equal(recheck, want);
                if (!verified) {
                    log.warn("[WorldQuest] read-back did not confirm withdraw {} from {}: before={} expected={} "
                                    + "now={}{} reason={}", delta.toPlainString(), owner, before.toPlainString(),
                            want.toPlainString(), recheck == null ? "UNREADABLE" : recheck.toPlainString(),
                            staleInstance ? " STALE-INSTANCE" : "", reason);
                }
                return new Move(true, before, recheck, verified, null, result, attempt);
            }

            lastError = result;
            lastResult = result;
            if (!"FAILED".equals(result)) {
                // NOT_ENOUGH_FUNDS, INVALID, CANCELLED … — deterministic; nothing moved.
                return new Move(false, before, recheck, equal(recheck, before), result, result, attempt);
            }
            // FAILED = a Post-event subscriber threw; only the balance says whether the write happened.
            if (equal(recheck, want)) {
                log.warn("[WorldQuest] provider reported FAILED for {} but the balance moved {} -> {} — counting as done",
                        owner, before.toPlainString(), recheck.toPlainString());
                return new Move(true, before, recheck, true, null, "FAILED_BUT_APPLIED", attempt);
            }
        }
        return new Move(false, startedAt, latest, equal(latest, startedAt),
                lastError + " after " + MAX_ATTEMPTS + " attempts", lastResult, attempt);
    }

    private BigDecimal round(BigDecimal v) {
        return v.setScale(decimals, RoundingMode.HALF_UP);
    }

    private static boolean equal(BigDecimal a, BigDecimal b) {
        return a != null && b != null && a.compareTo(b) == 0;
    }

    private BigDecimal read(Account account, UUID owner) {
        try {
            BigDecimal balance = account.balance();
            return balance == null ? null : round(balance);
        } catch (Throwable t) {
            log.warn("[WorldQuest] could not read the balance of {}: {}", owner, String.valueOf(t));
            return null;
        }
    }

    /** A fresh Account every single time — never store the result. */
    private Account account(UUID owner) {
        try {
            return await(EconomyService.instance().account(owner));
        } catch (Throwable t) {
            log.warn("[WorldQuest] could not get an account for {}: {}", owner, String.valueOf(t));
            return null;
        }
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
}
