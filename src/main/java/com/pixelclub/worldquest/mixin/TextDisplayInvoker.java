package com.pixelclub.worldquest.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** {@code TextDisplay} setters are private; the hologram is never added to a level. */
@Mixin(Display.TextDisplay.class)
public interface TextDisplayInvoker {

    @Invoker("setText")
    void pxcwq$setText(Component text);

    @Invoker("setLineWidth")
    void pxcwq$setLineWidth(int width);

    @Invoker("setBackgroundColor")
    void pxcwq$setBackgroundColor(int color);

    @Invoker("setFlags")
    void pxcwq$setFlags(byte flags);
}
