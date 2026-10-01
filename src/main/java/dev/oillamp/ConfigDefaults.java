package dev.oillamp;

import java.net.URI;
import java.util.Optional;

import sprouts.Association;
import sprouts.Tuple;

/// oillamp's built-in configuration: the values a lamp gets for every setting its files do not
/// mention.
///
/// The commented `oillamp.toml` written into a new lamp ([GeneratedFileTextUtil#defaultConfig])
/// must describe exactly these values. A scenario in `ConfiguringALampSpec` checks that.
final class ConfigDefaults {

    private ConfigDefaults() {}

    public static LampConfig lampConfig() {
        return new LampConfig(
            new LampConfig.Display(1920, 1080, 1.0, GpuMode.AUTO, WindowLayout.FLOATING),
            new LampConfig.Viewer(true, ClipboardMode.TO_AGENT, false, 30),
            new LampConfig.Terminal.Auto(),
            new LampConfig.Recording(false, "libx264", 30, 10, 14, 20),
            new LampConfig.Limits("16g", 0, 8192),
            NetworkPolicy.shippedDefault(),
            Tuple.of(Forward.class),
            Optional.empty(),
            new LampConfig.Model(URI.create("https://api.eu.edenai.run"), "EDENAI_API_KEY"),
            new LampConfig.Git(GitIdentity.GENIE, "", ""),
            new LampConfig.AgentTools(
                    Tuple.of(String.class, "opencode", "pi"),
                    Association.betweenSorted(String.class, String.class)
                            .put("opencode", "latest").put("pi", "latest")),
            new LampConfig.Image("docker.io/library/debian:trixie", "24", "temurin-25-jdk",
                    Tuple.of(String.class)),
            new LampConfig.Host(true),
            new LampConfig.Timeouts(60, 60, 15),
            new LampConfig.Schedule(false, 10, 15, 14, 48, 30, 8));
    }
}
