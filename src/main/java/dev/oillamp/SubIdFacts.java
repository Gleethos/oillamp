package dev.oillamp;

import sprouts.Tuple;

/**
 * What {@code /etc/subuid} and {@code /etc/subgid} say about this user — spec §11.2.
 *
 * <p>Rootless podman needs a block of subordinate ids to map container users onto. oillamp needs
 * at least 65536 of them, because the sandbox uses two container users and the infra user must
 * land on an id the agent cannot become (D-14, NFR-06).
 *
 * <p>Deliberately <b>package-private</b>: parsed {@code /etc/subuid} and {@code /etc/subgid}.
 */
sealed interface SubIdFacts {

    /** A usable range already exists. */
    record Present(IdRange uidRange, IdRange gidRange) implements SubIdFacts {}

    /**
     * No usable range. Carries every range already allocated to <em>anyone</em>, because the
     * new one has to avoid all of them — see {@link SubIdAllocator}.
     */
    record Missing(Tuple<IdRange> allocatedUidRanges, Tuple<IdRange> allocatedGidRanges) implements SubIdFacts {}

    /** The minimum block size rootless podman needs for a full container id space. */
    int REQUIRED_SIZE = 65536;
}
