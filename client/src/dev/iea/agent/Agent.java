package dev.iea.agent;

import java.lang.instrument.Instrumentation;

/**
 * Java agent entry point. Added to the game JVM via -javaagent:iea-agent.jar.
 * Runs before Minecraft's main() and installs the bytecode transformer.
 */
public final class Agent {
    public static Instrumentation inst; // kept for runtime class discovery (mappings)

    public static void premain(String args, Instrumentation in) {
        System.out.println("[IEA] agent premain loaded");
        inst = in;
        in.addTransformer(new Transformer(), true);
    }

    // Allows attaching at runtime too (not used by the launcher, but harmless).
    public static void agentmain(String args, Instrumentation inst) {
        premain(args, inst);
    }
}
