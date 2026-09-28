package com.pixelclub.worldquest.econ;

import java.math.BigDecimal;
import java.util.UUID;

/** No Impactor on this server: every donation is refused with a reason, nothing is taken. */
public final class NoEconomy implements Econ {

    private final String why;

    public NoEconomy(String why) {
        this.why = why;
    }

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public String describe() {
        return "none (" + why + ")";
    }

    @Override
    public int decimals() {
        return 2;
    }

    @Override
    public BigDecimal balance(UUID owner) {
        return null;
    }

    @Override
    public Move withdraw(UUID owner, BigDecimal amount, String reason) {
        return Move.fail("no economy provider: " + why);
    }
}
