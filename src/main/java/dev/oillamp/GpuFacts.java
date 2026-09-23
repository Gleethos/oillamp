package dev.oillamp;

import java.nio.file.Path;

import sprouts.Tuple;

/// The GPU render devices (`/dev/dri/renderD*`) found on the host. Input to [Gpu#decide],
/// which can always fall back to software rendering.
record GpuFacts(Tuple<RenderNode> renderNodes) {

    /// Kernel drivers whose open-source Mesa graphics stack works in the sandbox.
    public static final Tuple<String> SUPPORTED_DRIVERS =
            Tuple.of(String.class, "i915", "xe", "amdgpu", "radeon", "nouveau", "virtio_gpu");

    public static GpuFacts none() { return new GpuFacts(Tuple.of(RenderNode.class)); }

    /// A `/dev/dri/renderD*` node, the group that owns it, and the kernel driver behind it.
    public record RenderNode(Path path, String owningGroup, String driver) {
        public boolean hasSupportedDriver() { return SUPPORTED_DRIVERS.contains(driver); }
    }
}
