package dev.oillamp;

/** The podman container name for a lamp — {@code oillamp-<agentId>}, spec §10.2. */
record ContainerName(String value) {

    public ContainerName {
        if (!value.matches("oillamp-[a-z2-7]{8}"))
            throw new IllegalArgumentException("Not an oillamp container name: '" + value + "'");
    }

    public static ContainerName of(AgentId id) {
        return new ContainerName("oillamp-" + id.value());
    }

    @Override public String toString() { return value; }
}
