package com.pixelclub.worldquest.holo;

import com.pixelclub.worldquest.config.WqConfig;
import com.pixelclub.worldquest.state.WqState;
import com.pixelclub.worldquest.util.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Builds the two hologram texts (title, body) as legacy strings; the string doubles as the change key. */
public final class HoloText {

    private HoloText() {
    }

    public static String title(WqConfig cfg, WqConfig.QuestDef q) {
        WqConfig.Messages m = cfg.messages;
        return m.holoTitle + "\n" + Text.fill(m.holoName, Map.of("quest", q.name));
    }

    public static String body(WqConfig cfg, WqConfig.QuestDef q, WqState.QuestState s) {
        WqConfig.Messages m = cfg.messages;
        WqConfig.Hologram h = q.hologram;
        List<String> lines = new ArrayList<>();
        String cell = "■";
        if (q.conquerors.enabled) {
            long count = s.conquerors.size();
            lines.add(Text.fill(m.holoConquerors, Map.of("label", q.conquerors.label,
                    "count", Text.num(count), "target", Text.num(q.conquerors.target))));
            lines.add(Text.bar(count, q.conquerors.target, h.barLength, "&a", "&8", cell.charAt(0)));
        }
        if (q.fund.enabled) {
            lines.add(Text.fill(m.holoFund, Map.of("label", q.fund.label,
                    "points", Text.num(s.fundPoints), "target", Text.num(q.fund.targetPoints))));
            lines.add(Text.bar(s.fundPoints, q.fund.targetPoints, h.barLength, "&6", "&8", cell.charAt(0)));
            lines.add(Text.fill(m.holoFundDetail, Map.of("money", Text.baht(s.fundMoney),
                    "itemPoints", Text.num(s.fundItemPoints))));
        }
        if (h.recentConquerors > 0 && !s.conquerors.isEmpty()) {
            List<String> recent = s.conquerors.values().stream()
                    .sorted(Comparator.comparingLong((WqState.Conqueror c) -> c.at).reversed())
                    .limit(h.recentConquerors)
                    .map(c -> c.name == null ? "?" : c.name)
                    .collect(Collectors.toList());
            lines.add(Text.fill(m.holoRecent, Map.of("names", String.join(" &7· &f", recent))));
        }
        if (h.topDonors > 0) {
            List<String> top = s.players.values().stream()
                    .filter(r -> r.fundPoints > 0)
                    .sorted(Comparator.comparingLong((WqState.PlayerRec r) -> r.fundPoints).reversed()
                            .thenComparingLong(r -> r.firstAt))
                    .limit(h.topDonors)
                    .map(r -> r.name == null ? "?" : r.name)
                    .collect(Collectors.toList());
            if (!top.isEmpty()) {
                lines.add(Text.fill(m.holoTop, Map.of("names", String.join(" &7· &f", top))));
            }
        }
        boolean offerHint = q.fund.enabled && q.fund.acceptItems && h.rightClickOffer && h.monument != null
                && h.monument.enabled;
        switch (s.phase) {
            case WqState.Phase.REACHED -> lines.add(offerHint && q.acceptAfterReached
                    ? m.holoReached + "\n" + m.holoHintOffer : m.holoReached);
            case WqState.Phase.OPENED -> lines.add(Text.fill(m.holoOpened, Map.of("quest", q.name)));
            default -> lines.add(offerHint ? m.holoHintOffer : m.holoHint);
        }
        return String.join("\n", lines);
    }
}
