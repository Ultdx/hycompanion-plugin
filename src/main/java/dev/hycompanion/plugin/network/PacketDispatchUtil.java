package dev.hycompanion.plugin.network;

import com.hypixel.hytale.server.core.universe.PlayerRef;
import dev.hycompanion.plugin.utils.PluginLogger;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PacketDispatchUtil {
    private static final AtomicBoolean COMPAT_WARNING_LOGGED = new AtomicBoolean(false);

    private PacketDispatchUtil() {
    }

    /**
     * Send packet to a player in a runtime-compatible way.
     * Some server builds don't expose PacketHandler.write(Packet), so direct calls can throw NoSuchMethodError.
     */
    public static boolean trySendPacketToPlayer(PlayerRef playerRef, Object packet, PluginLogger logger) {
        if (playerRef == null || packet == null) {
            return false;
        }

        Object packetHandler;
        try {
            packetHandler = playerRef.getPacketHandler();
        } catch (Throwable t) {
            if (logger != null) {
                logger.debug("[Hycompanion] Could not access packet handler: " + t.getMessage());
            }
            return false;
        }
        if (packetHandler == null) {
            return false;
        }

        try {
            Method directOneArg = findSingleArgCompatibleMethod(packetHandler.getClass(), packet.getClass(), "writeNoCache", "write");
            if (directOneArg != null) {
                directOneArg.invoke(packetHandler, packet);
                return true;
            }

            // Fallback: some versions expose writePacket(packet, cacheFlag)
            Method writePacketMethod = findWritePacketMethod(packetHandler.getClass(), packet.getClass());
            if (writePacketMethod != null) {
                writePacketMethod.invoke(packetHandler, packet, true);
                return true;
            }

            if (logger != null && COMPAT_WARNING_LOGGED.compareAndSet(false, true)) {
                logger.warn("[Hycompanion] No compatible packet send method found on packet handler class: " +
                    packetHandler.getClass().getName());
            }
        } catch (Throwable t) {
            // Never allow packet dispatch failures to crash WorldThread.
            if (logger != null) {
                logger.debug("[Hycompanion] Packet dispatch failed safely: " + t.getMessage());
            }
        }

        return false;
    }

    private static Method findSingleArgCompatibleMethod(Class<?> handlerClass, Class<?> packetClass, String... methodNames) {
        for (String methodName : methodNames) {
            for (Method method : handlerClass.getMethods()) {
                if (!method.getName().equals(methodName)) {
                    continue;
                }
                if (method.getParameterCount() != 1) {
                    continue;
                }
                Class<?> paramType = method.getParameterTypes()[0];
                if (!paramType.isAssignableFrom(packetClass)) {
                    continue;
                }
                return method;
            }
        }
        return null;
    }

    private static Method findWritePacketMethod(Class<?> handlerClass, Class<?> packetClass) {
        for (Method method : handlerClass.getMethods()) {
            if (!method.getName().equals("writePacket")) {
                continue;
            }
            if (method.getParameterCount() != 2) {
                continue;
            }
            Class<?>[] paramTypes = method.getParameterTypes();
            if (paramTypes[0].isAssignableFrom(packetClass) && (paramTypes[1] == boolean.class || paramTypes[1] == Boolean.class)) {
                return method;
            }
        }
        return null;
    }
}
