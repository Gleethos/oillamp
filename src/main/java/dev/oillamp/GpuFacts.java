package dev.oillamp;

import java.nio.file.Path;

import sprouts.Tuple;

/**
 * DRM render nodes found on the host — spec §14.3.
 *
 * <p>GPU acceleration is welcome but must never block a session (D-24), so this is only ever an
 * input to a decision that can always fall back to software rendering.
 */
record GpuFacts(Tuple<RenderNode> renderNodes) {

    /** Drivers whose open Mesa stack is known to work in the sandbox (§14.3). */
    public static final Tuple<String> SUPPORTED_DRIVERS =
            Tuple.of(String.class, "i915", "xe", "amdgpu", "radeon", "nouveau", "virtio_gpu");

    public static GpuFacts none() { return new GpuFacts(Tuple.of(RenderNode.class)); }

    /** A {@code /dev/dri/renderD*} node, the group that owns it, and the kernel driver behind it. */
    public record RenderNode(Path path, String owningGroup, String driver) {
        public boolean hasSupportedDriver() { return SUPPORTED_DRIVERS.contains(driver); }
    }
}
