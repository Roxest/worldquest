package com.pixelclub.worldquest;

import com.pixelclub.worldquest.config.WqConfig;
import com.pixelclub.worldquest.holo.Geometry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GeometryTest {

    // seal at the origin: radius 3.46 (13.84 px at scale 4), base 0, top 10.8
    private static final double R = Geometry.px(13.84, 4.0);
    private static final double TOP = Geometry.px(21.5 + 23.9, 4.0);

    private static double entry(double ox, double oy, double oz, double dx, double dy, double dz, double reach) {
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return Geometry.rayCylinderEntry(ox, oy, oz, dx / len, dy / len, dz / len, 0, 0, R, 0, TOP, reach);
    }

    @Test
    void lookingStraightAtTheSealFromFiveBlocksOutHitsItsSurface() {
        double t = entry(5, 1.62, 0, -1, 0, 0, 6.0);
        assertEquals(5 - R, t, 1e-9);
    }

    @Test
    void lookingAwayOrPastItMisses() {
        assertEquals(-1, entry(5, 1.62, 0, 1, 0, 0, 6.0));          // facing away
        assertEquals(-1, entry(5, 1.62, 5, -1, 0, 0, 6.0));         // passes 5 blocks to the side
    }

    @Test
    void outOfReachIsAMiss() {
        assertEquals(-1, entry(20, 1.62, 0, -1, 0, 0, 6.0));
        assertTrue(entry(20, 1.62, 0, -1, 0, 0, 20.0) > 0);
    }

    @Test
    void aRayOverTheTopMissesAndOneDippingIntoTheTopCapHits() {
        assertEquals(-1, entry(5, TOP + 1, 0, -1, 0, 0, 6.0));       // level, above the seal
        assertTrue(entry(1, TOP + 1, 0, -1, -1, 0, 6.0) >= 0);       // down into the top from above
    }

    @Test
    void standingInsideTheFootprintEntersAtZero() {
        assertEquals(0, entry(0.5, 1.62, 0, 0, 0, 1, 6.0));
    }

    @Test
    void lookingStraightDownOutsideTheCircleMisses() {
        assertEquals(-1, Geometry.rayCylinderEntry(5, 20, 0, 0, -1, 0, 0, 0, R, 0, TOP, 30));
        assertEquals(20 - TOP, Geometry.rayCylinderEntry(1, 20, 0, 0, -1, 0, 0, 0, R, 0, TOP, 30), 1e-9);
    }

    @Test
    void lineCountAndTextHeight() {
        assertEquals(1, Geometry.lineCount(""));
        assertEquals(3, Geometry.lineCount("a\nb\nc"));
        assertEquals(0.25 * 1.3 * 3, Geometry.textHeight(3, 0.25, 1.3), 1e-12);
    }

    @Test
    void defaultsStackTheTextAboveTheSeal() {
        WqConfig.Hologram h = new WqConfig.Hologram();
        assertEquals("stacked", h.layout);
        WqConfig.Monument m = h.monument;
        double top = Geometry.px(m.modelBaseBelowCenterPx + m.modelTopAboveCenterPx, m.scale);
        assertTrue(top > 10 && top < 12, "a big seal: ~11 blocks, was " + top);
        assertTrue(h.stackGap > 0);
    }

    @Test
    void theHeldItemShardIsMatchedByItsExactName() {
        WqConfig.ItemDef d = WqConfig.ItemDef.heldItemShard();
        assertEquals("minecraft:prismarine_shard", d.id);
        assertEquals("§b§lHeld Item Shard", d.requireCustomName);
        assertTrue(d.allowLore);
        assertEquals(1, d.points);
        assertTrue(new WqConfig().itemIndex().containsKey("minecraft:prismarine_shard"));
    }
}
