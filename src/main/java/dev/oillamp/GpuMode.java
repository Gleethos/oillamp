package dev.oillamp;

/**
 * {@code display.gpu} — spec §14.3 (D-24). GPU is welcome, but must never block a session.
 *
 * <p>Deliberately <b>package-private</b>: one configuration value.
 */
enum GpuMode {
    /** Use the GPU if everything lines up; fall back to software silently otherwise. */
    AUTO,
    /** Insist on the GPU; a missing render node is an error ({@code OIL-GPU-003}). */
    ON,
    /** Never touch the GPU: pixman renderer and software GL. */
    OFF;

    public String configName() { return name().toLowerCase(java.util.Locale.ROOT); }
}
