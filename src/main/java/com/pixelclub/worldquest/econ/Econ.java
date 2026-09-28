package com.pixelclub.worldquest.econ;

import java.math.BigDecimal;
import java.util.UUID;

/** The only money operation this mod performs is taking a donation; nothing is ever paid out. */
public interface Econ {

    boolean available();

    String describe();

    int decimals();

    /** Current balance, or null when it cannot be read. Blocks up to a few seconds — never call on the server thread. */
    BigDecimal balance(UUID owner);

    /** Blocks up to a few seconds — never call on the server thread. */
    Move withdraw(UUID owner, BigDecimal amount, String reason);

    /**
     * @param ok         the balance moved by exactly the amount (or the provider said so and nothing contradicts it)
     * @param before     balance read just before the successful attempt (null if never read)
     * @param after      balance read back after it (null if unreadable)
     * @param readBackOk the read-back equals before − amount
     * @param error      the refusal when {@code ok} is false
     * @param result     the provider's result constant, for the ledger
     * @param attempts   how many times the call was made
     */
    record Move(boolean ok, BigDecimal before, BigDecimal after, boolean readBackOk,
                String error, String result, int attempts) {

        public static Move fail(String error) {
            return new Move(false, null, null, false, error, "NONE", 0);
        }

        /** Nothing moved and nothing could have: a deterministic refusal such as NOT_ENOUGH_FUNDS. */
        public boolean definitelyDidNotMove() {
            return !ok && result != null && !result.equals("FAILED") && !result.equals("NONE")
                    && !result.equals("UNDECIDED");
        }

        /** The provider threw after the balance moved: not known whether it will be saved. */
        public boolean undecided() {
            return !ok && "UNDECIDED".equals(result);
        }
    }
}
