package com.pixelclub.worldquest.mixin;

import com.mojang.math.Transformation;
import net.minecraft.util.Brightness;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The Display setters are private in 1.21.1; the packet-only hologram sets them before its data is sent. */
@Mixin(Display.class)
public interface DisplayInvoker {

    @Invoker("setBillboardConstraints")
    void pxcwq$setBillboardConstraints(Display.BillboardConstraints constraints);

    @Invoker("setTransformation")
    void pxcwq$setTransformation(Transformation transformation);

    @Invoker("setBrightnessOverride")
    void pxcwq$setBrightnessOverride(Brightness brightness);

    @Invoker("setViewRange")
    void pxcwq$setViewRange(float range);

    @Invoker("setTransformationInterpolationDuration")
    void pxcwq$setTransformationInterpolationDuration(int ticks);

    @Invoker("setTransformationInterpolationDelay")
    void pxcwq$setTransformationInterpolationDelay(int ticks);
}
