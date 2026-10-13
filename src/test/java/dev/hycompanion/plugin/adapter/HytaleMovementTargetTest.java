package dev.hycompanion.plugin.adapter;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.modules.entity.component.BoundingBox;
import com.hypixel.hytale.server.core.modules.entity.component.Intangible;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.util.EntityDetectionUtil;
import dev.hycompanion.plugin.api.Location;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import sun.reflect.ReflectionFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HytaleMovementTargetTest {
    private static EntityModule previousModule;
    private static Field moduleInstance;
    private static ComponentType<EntityStore, Intangible> intangibleType;
    private static final List<ComponentType<EntityStore, ?>> registered = new ArrayList<>();

    @BeforeAll
    static void registerMinimalServerComponents() throws Exception {
        moduleInstance = EntityModule.class.getDeclaredField("instance");
        moduleInstance.setAccessible(true);
        previousModule = (EntityModule) moduleInstance.get(null);
        assertNull(previousModule, "This fixture must run without a live Hytale server");
        // Only bootstrap component lookups. Running the real EntityModule constructor requires a full server.
        EntityModule module = (EntityModule) ReflectionFactory.getReflectionFactory()
                .newConstructorForSerialization(EntityModule.class, Object.class.getDeclaredConstructor())
                .newInstance();
        register(module, "transformComponentType", TransformComponent.class, TransformComponent::new);
        register(module, "uuidComponentType", UUIDComponent.class, UUIDComponent::randomUUID);
        register(module, "boundingBoxComponentType", BoundingBox.class, BoundingBox::new);
        intangibleType = register(module, "intangibleComponentType", Intangible.class, () -> Intangible.INSTANCE);
        register(module, "playerComponentType", Player.class, () -> null);
        moduleInstance.set(null, module);
    }

    private static <T extends com.hypixel.hytale.component.Component<EntityStore>> ComponentType<EntityStore, T>
            register(EntityModule module, String fieldName, Class<T> type, java.util.function.Supplier<T> supplier)
            throws Exception {
        ComponentType<EntityStore, T> componentType = EntityStore.REGISTRY.registerComponent(type, supplier);
        registered.add(componentType);
        Field field = EntityModule.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(module, componentType);
        return componentType;
    }

    @AfterAll
    static void restoreServerComponents() throws Exception {
        if (moduleInstance != null) moduleInstance.set(null, previousModule);
        for (ComponentType<EntityStore, ?> type : registered.reversed()) {
            EntityStore.REGISTRY.unregisterComponent(type);
        }
    }

    @Test
    void movementTargetPassesTheActualServerDetectionRule() {
        Location destination = Location.of(-707, 118, 53);
        Holder<EntityStore> target = HytaleServerAdapter.createMovementTarget(destination);
        assertTrue(isDetectable(target), "PreBehaviourTick must retain the movement target");
        assertNull(target.getComponent(intangibleType));
        assertNotNull(target.getComponent(UUIDComponent.getComponentType()));
        assertNotNull(target.getComponent(BoundingBox.getComponentType()));
        var position = target.getComponent(TransformComponent.getComponentType()).getPosition();
        assertEquals(destination.x(), position.x());
        assertEquals(destination.y(), position.y());
        assertEquals(destination.z(), position.z());
    }

    @Test
    void reproducesTargetRejectionWhenTheOldIntangibleComponentIsPresent() {
        Holder<EntityStore> target = HytaleServerAdapter.createMovementTarget(Location.of(-707, 118, 53));
        target.ensureComponent(intangibleType);
        assertFalse(isDetectable(target), "The server clears this target before evaluating MoveBehavior");
    }

    @SuppressWarnings("unchecked")
    private boolean isDetectable(Holder<EntityStore> target) {
        ComponentAccessor<EntityStore> accessor = (ComponentAccessor<EntityStore>) Proxy.newProxyInstance(
                ComponentAccessor.class.getClassLoader(), new Class<?>[] {ComponentAccessor.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getArchetype" -> target.getArchetype();
                    case "getComponent" -> target.getComponent((ComponentType<EntityStore, ?>) args[1]);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        // The accessor resolves the holder; the detection rule never reads the Ref's store.
        return EntityDetectionUtil.isDetectableByNPCs(new Ref<>(null, 0), accessor);
    }
}
