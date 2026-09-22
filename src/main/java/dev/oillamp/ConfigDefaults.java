package dev.oillamp;

import java.util.Optional;

import sprouts.Association;
import sprouts.Tuple;

/**
 * oillamp's built-in configuration — the bottom of the precedence chain of spec §20.1.
 *
 * <p>These are the effective values a lamp gets when its {@code oillamp.toml} says nothing.
 * The commented template that {@code oillamp at} writes into a new lamp (§20.2) must agree with
 * them exactly; a scenario asserts that, so the documentation a user reads and the behaviour
 * they get can never drift apart.
 *
 * <p>Deliberately <b>package-private</b>: defaults are meant to change between releases. Freezing
 * them in the API would turn every improved default into a compatibility question.
 */
final class ConfigDefaults {

    private ConfigDefaults() {}

    public static LampConfig lampConfig() {
        return new LampConfig(
            new LampConfig.Display(1920, 1080, 1.0, GpuMode.AUTO),
            new LampConfig.Viewer(true, ClipboardMode.TO_AGENT, false, 30),
            new LampConfig.Terminal.Auto(),
            new LampConfig.Recording(false, "libx264", 30, 10, 14, 20),
            new LampConfig.Limits("16g", 0, 8192),
            NetworkPolicy.shippedDefault(),
            Tuple.of(Forward.class),
            Optional.empty(),
            new LampConfig.AgentTools(
                    Tuple.of(String.class, "opencode", "pi"),
                    Association.betweenSorted(String.class, String.class)
                            .put("opencode", "latest").put("pi", "latest")),
            new LampConfig.Image("docker.io/library/debian:trixie", "24", "temurin-25-jdk",
                    Tuple.of(String.class)),
            new LampConfig.Host(true),
            new LampConfig.Timeouts(45, 60, 15));
    }
}
