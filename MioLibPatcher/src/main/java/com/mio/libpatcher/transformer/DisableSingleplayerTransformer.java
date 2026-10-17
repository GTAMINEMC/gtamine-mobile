package com.mio.libpatcher.transformer;

import com.mio.libpatcher.util.LogUtil;

import java.util.Arrays;
import java.util.List;

import javassist.CtClass;
import javassist.CtMethod;

/** Disables the Singleplayer button callback in the fixed Minecraft 1.21.11 client. */
public final class DisableSingleplayerTransformer implements BaseTransformer {
    @Override
    public List<String> getTargetClassNames() {
        // gsd is net.minecraft.client.gui.screens.TitleScreen in the official
        // 1.21.11 client mappings. Keep the named form for development jars.
        return Arrays.asList("gsd", "net.minecraft.client.gui.screens.TitleScreen");
    }

    @Override
    public void transform(CtClass clazz) throws Throwable {
        for (CtMethod method : clazz.getDeclaredMethods()) {
            CtClass[] parameters = method.getParameterTypes();
            boolean obfuscatedCallback = method.getName().equals("d")
                    && method.getReturnType().equals(CtClass.voidType)
                    && parameters.length == 1;
            boolean namedCallback = method.getName().equals("lambda$createNormalMenuOptions$8")
                    && method.getReturnType().equals(CtClass.voidType)
                    && parameters.length == 1;
            if (!obfuscatedCallback && !namedCallback) continue;

            method.setBody("{ return; }");
            LogUtil.info("GTAMINE: disabled Minecraft singleplayer menu callback");
            return;
        }
        LogUtil.error("GTAMINE: singleplayer callback was not found in " + clazz.getName());
    }
}
