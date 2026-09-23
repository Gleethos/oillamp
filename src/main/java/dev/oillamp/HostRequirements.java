package dev.oillamp;

import sprouts.Association;
import sprouts.Tuple;
import sprouts.ValueSet;

/**
 * The host packages oillamp needs, each with the reason it is needed.
 *
 * <p>The reasons are shown to the user before anything is installed, so they know what is about
 * to change on their machine and why. The list is keyed by {@link DistroFamily}; today every
 * family gets the Debian list, which is also what is printed for manual installation elsewhere.
 */
record HostRequirements(DistroFamily family, Tuple<String> packages,
                               Association<String, String> reasons) {

    public static HostRequirements forFamily(DistroFamily family) {
        return switch (family) {
            case DEBIAN -> debian();
            case RPM, ARCH, UNKNOWN -> new HostRequirements(family, debian().packages(), debian().reasons());
        };
    }

    private static HostRequirements debian() {
        Association<String, String> why = Association.betweenSorted(String.class, String.class)
            .put("podman",           "runs the sandbox container, rootless and without a daemon")
            .put("crun",             "the OCI runtime podman uses to start the container; Ubuntu "
                                   + "defaults to runc, which cannot pass the GPU render group in")
            .put("slirp4netns",      "gives rootless podman a network while the image is built; "
                                   + "the sandbox itself then runs with --network=none")
            .put("uidmap",           "newuidmap/newgidmap, which rootless podman needs to map container users")
            .put("catatonit",        "the init process inside the container, so orphaned processes get reaped")
            .put("socat",            "bridges the SSH connection over a Unix socket instead of a TCP port")
            .put("openssh-client",   "ssh and ssh-keygen for the terminal window and the per-lamp keys")
            .put("tigervnc-viewer",  "the window in which you watch the agent's desktop");
        Tuple<String> packages = Tuple.of(String.class, why.keySet());
        return new HostRequirements(DistroFamily.DEBIAN, packages, why);
    }

    /** The packages from this table that are not installed yet, in table order. */
    public Tuple<String> missingFrom(ValueSet<String> installed) {
        Tuple<String> missing = Tuple.of(String.class);
        for (String pkg : packages)
            if (!installed.contains(pkg)) missing = missing.add(pkg);
        return missing;
    }

    /** The command oillamp runs to install the packages, and prints when it is not allowed to. */
    public String installCommand(Tuple<String> missing) {
        return "sudo apt-get install -y --no-install-recommends " + String.join(" ", missing);
    }
}
