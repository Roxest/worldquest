package com.pixelclub.worldquest.util;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.Map;

/** Legacy &-code text (TextUtil from legendaryreroll-fabric) plus the number formats the quest shows. */
public final class Text {

    private Text() {
    }

    /** Parses a legacy '&'/'§'-code string into real Style runs (chat does not parse § inside literals). */
    public static MutableComponent of(String legacy) {
        MutableComponent root = Component.empty().setStyle(Style.EMPTY.withItalic(false));
        if (legacy == null || legacy.isEmpty()) return root;
        Style run = Style.EMPTY;
        StringBuilder buf = new StringBuilder();
        int len = legacy.length();
        for (int i = 0; i < len; i++) {
            char c = legacy.charAt(i);
            if ((c == '§' || c == '&') && i + 1 < len) {
                ChatFormatting fmt = ChatFormatting.getByCode(Character.toLowerCase(legacy.charAt(i + 1)));
                if (fmt != null) {
                    if (buf.length() > 0) {
                        root.append(Component.literal(buf.toString()).setStyle(run));
                        buf.setLength(0);
                    }
                    run = fmt == ChatFormatting.RESET ? Style.EMPTY : run.applyLegacyFormat(fmt);
                    i++;
                    continue;
                }
            }
            buf.append(c);
        }
        if (buf.length() > 0) root.append(Component.literal(buf.toString()).setStyle(run));
        return root;
    }

    /** {key} placeholders; values are inserted verbatim (they may carry their own &-codes). */
    public static String fill(String template, Map<String, ?> values) {
        if (template == null) return "";
        String out = template;
        for (Map.Entry<String, ?> e : values.entrySet()) {
            out = out.replace("{" + e.getKey() + "}", String.valueOf(e.getValue()));
        }
        return out;
    }

    public static MutableComponent button(String legacy, String command, String hoverLegacy) {
        MutableComponent c = of(legacy);
        Style style = Style.EMPTY.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command));
        if (hoverLegacy != null) {
            style = style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, of(hoverLegacy)));
        }
        return Component.empty().append(c).withStyle(style);
    }

    /** 1234567 → "1,234,567". */
    public static String num(long n) {
        return NumberFormat.getIntegerInstance(Locale.US).format(n);
    }

    /**
     * Money the way Thai players read it: below a million with commas, from a million up in ล้าน with at most one
     * decimal ("120.5 ล้าน"), never rounding a non-zero amount up to a whole million it has not reached.
     */
    public static String baht(long amount) {
        if (Math.abs(amount) < 1_000_000L) {
            return num(amount);
        }
        BigDecimal m = BigDecimal.valueOf(amount).divide(BigDecimal.valueOf(1_000_000L), 1, RoundingMode.DOWN);
        String s = m.stripTrailingZeros().toPlainString();
        return s + " ล้าน";
    }

    /** Integer percent, floored, capped at 999; 0 target reads as 100. */
    public static int percent(long value, long target) {
        if (target <= 0) return 100;
        long p = value * 100L / target;
        return (int) Math.max(0, Math.min(999, p));
    }

    /** A bar of {@code length} cells, filled cells first; the fill never shows full before the goal is met. */
    public static String bar(long value, long target, int length, String fillColor, String emptyColor, char cell) {
        int filled;
        if (target <= 0 || value >= target) {
            filled = length;
        } else {
            filled = (int) Math.min(length - 1, (value * (long) length) / target);
            if (value > 0 && filled == 0) filled = 1;
        }
        StringBuilder sb = new StringBuilder(length + 8);
        sb.append(fillColor);
        for (int i = 0; i < filled; i++) sb.append(cell);
        sb.append(emptyColor);
        for (int i = filled; i < length; i++) sb.append(cell);
        return sb.toString();
    }
}
