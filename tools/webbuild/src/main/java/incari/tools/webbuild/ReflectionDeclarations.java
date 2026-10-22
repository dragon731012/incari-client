package incari.tools.webbuild;

import org.teavm.extension.introspect.IntrospectClass;
import org.teavm.extension.spi.reflection.SimpleReflectionPolicy;

/**
 * The few members libraries look up by reflection on paths the browser build keeps, declared so
 * TeaVM compiles their metadata (it keeps none by default).
 *
 * <p>Netty's buffer classes name methods for {@code ResourceLeakDetector.addExclusions} in their
 * static initializers, which finds them with {@code getDeclaredMethods()} and throws when one is
 * missing: without these, the first buffer allocation fails.
 *
 * <p>Registered in {@code META-INF/services}; TeaVM loads it from the compile's class loader.
 */
public final class ReflectionDeclarations extends SimpleReflectionPolicy {

    @Override
    protected void setup() {
        type("io.netty.buffer.AbstractByteBufAllocator").reflectableMethods(named("toLeakAwareBuffer"));
        type("io.netty.buffer.AdvancedLeakAwareByteBuf")
                .reflectableMethods(named("touch").or(named("recordLeakNonRefCountingOperation")));
        type("io.netty.util.ReferenceCountUtil").reflectableMethods(named("touch"));
        // Singleplayer's in-memory connection: Netty's bootstraps make the channels from their
        // classes (ReflectiveChannelFactory: getConstructor().newInstance()), the server's in
        // ServerConnectionListener.startMemoryChannel and the client's in Connection.connectToLocalServer.
        type("io.netty.channel.local.LocalServerChannel").reflectableMethods(named("<init>"));
        type("io.netty.channel.local.LocalChannel").reflectableMethods(named("<init>"));
        // The ender dragon's phases are made by reflection (EnderDragonPhase.createInstance:
        // getConstructor(EnderDragon.class).newInstance), when a dragon is created: on the client,
        // as soon as the player sees one in the End (the screenshot scenes found it).
        selectClasses((IntrospectClass<?> cls) -> cls != null
                        && cls.name().startsWith(DRAGON_PHASES + "Dragon")
                        && cls.name().endsWith("Phase"))
                .reflectableMethods(named("<init>"));
    }

    private static final String DRAGON_PHASES = "net.minecraft.world.entity.boss.enderdragon.phases.";

    /**
     * Not {@code selectClass}: TeaVM also asks policies about classes it couldn't introspect,
     * passing null, which {@code selectClass}'s predicate doesn't expect.
     */
    private ClassPolicy type(String name) {
        return selectClasses(
                (IntrospectClass<?> cls) -> cls != null && cls.name().equals(name));
    }
}
