package dev.oillamp;

/** The {@code display.gpu} setting: whether the sandbox desktop may use the host's graphics card. */
enum GpuMode {
    /** Use the GPU if every condition is met; otherwise draw in software and say why. */
    AUTO,
    /** Insist on the GPU; a missing render node is an error ({@code OIL-GPU-003}). */
    ON,
    /** Never touch the GPU: pixman renderer and software GL. */
    OFF;

    public String configName() { return name().toLowerCase(java.util.Locale.ROOT); }
}
