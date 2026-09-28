package com.pixelclub.worldquest.holo;

/**
 * Plain arithmetic for the seal: where a look ray enters it, and how tall a stack of text lines stands. Kept free of
 * Minecraft types so the unit tests can run it without a game.
 *
 * <p>The seal is treated as an upright cylinder around the anchor: the model turns slowly, so its square footprint
 * sweeps a circle, and a click anywhere inside that circle is a click on the seal.
 */
public final class Geometry {

    private Geometry() {
    }

    /**
     * Distance along the ray {@code o + t·d} (d need not be unit length; t is in units of |d|) at which it enters the
     * upright cylinder (centre cx/cz, radius r, from y0 to y1), or -1 when it misses or enters beyond maxT. A ray
     * that starts inside the cylinder enters at 0.
     */
    public static double rayCylinderEntry(double ox, double oy, double oz, double dx, double dy, double dz,
                                          double cx, double cz, double r, double y0, double y1, double maxT) {
        if (r <= 0 || y1 < y0) {
            return -1;
        }
        double fx = ox - cx;
        double fz = oz - cz;
        double a = dx * dx + dz * dz;
        double c = fx * fx + fz * fz - r * r;
        double inXZ;
        double outXZ;
        if (a < 1e-12) {
            // straight up or down: inside the circle for every t, or never
            if (c > 0) {
                return -1;
            }
            inXZ = Double.NEGATIVE_INFINITY;
            outXZ = Double.POSITIVE_INFINITY;
        } else {
            double b = fx * dx + fz * dz;
            double disc = b * b - a * c;
            if (disc < 0) {
                return -1;
            }
            double sq = Math.sqrt(disc);
            inXZ = (-b - sq) / a;
            outXZ = (-b + sq) / a;
        }
        double inY;
        double outY;
        if (Math.abs(dy) < 1e-12) {
            if (oy < y0 || oy > y1) {
                return -1;
            }
            inY = Double.NEGATIVE_INFINITY;
            outY = Double.POSITIVE_INFINITY;
        } else {
            double t0 = (y0 - oy) / dy;
            double t1 = (y1 - oy) / dy;
            inY = Math.min(t0, t1);
            outY = Math.max(t0, t1);
        }
        double enter = Math.max(0, Math.max(inXZ, inY));
        double exit = Math.min(outXZ, outY);
        if (enter > exit || enter > maxT) {
            return -1;
        }
        return enter;
    }

    /** Lines in a hologram text ("\n"-separated; an empty text still takes one line). */
    public static int lineCount(String text) {
        if (text == null || text.isEmpty()) {
            return 1;
        }
        int n = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }

    /** Height of a text display of {@code lines} lines at {@code scale}, blocks. */
    public static double textHeight(int lines, double lineHeight, double scale) {
        return Math.max(1, lines) * lineHeight * scale;
    }

    /** Blocks from the model centre down to the seal's base / up to its top, at this scale (16 px = 1 block). */
    public static double px(double pixels, double scale) {
        return pixels * scale / 16.0;
    }
}
