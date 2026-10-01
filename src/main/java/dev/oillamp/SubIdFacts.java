package dev.oillamp;

import sprouts.Tuple;

/// What `/etc/subuid` and `/etc/subgid` say about this user.
///
/// Rootless podman maps every container user other than the agent onto the user's block of
/// subordinate ids. oillamp needs a block of at least 65536, a full container id space. Container
/// root and the infra user (uid 1001) both land in this block, on ids that own nothing on the host.
sealed interface SubIdFacts {

    /// A usable range already exists.
    record Present(IdRange uidRange, IdRange gidRange) implements SubIdFacts {}

    /// No usable range. Carries every range already allocated to _anyone_, because the
    /// new one has to avoid all of them; see [SubIdRangeUtil].
    record Missing(Tuple<IdRange> allocatedUidRanges, Tuple<IdRange> allocatedGidRanges) implements SubIdFacts {}

    /// The smallest block of subordinate ids oillamp accepts: one full container id space.
    int REQUIRED_SIZE = 65536;
}
