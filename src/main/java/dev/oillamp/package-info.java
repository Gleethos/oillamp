/// oillamp gives an AI coding agent its own sandboxed Linux machine with a graphical desktop.
///
/// ## Five public types
///
/// Exactly five types in this package are `public`, and a test enforces that number:
///
/// - [dev.oillamp.OilLamp]: the entry point, `OilLamp.on(machine).run(argv)`
/// - [dev.oillamp.Machine]: everything oillamp does to the outside world goes through it
/// - [dev.oillamp.LampEvent]: what oillamp reports, as values
/// - [dev.oillamp.Problem]: what went wrong, with evidence and fixes
/// - [dev.oillamp.ExitStatus]: how the process ended
///
/// Everything else is package-private, so that it can be changed without breaking any caller.
/// Java enforces this: a package-private class cannot be used from another package. That is also
/// why all the code is in one package. Sub-packages would need public types to talk to each other,
/// and those would be visible to everyone.
///
/// A package-private class therefore needs no comment explaining its visibility. The five public
/// types each say why they are public. Before making a sixth type public, read their reasons and
/// update `TheShapeOfTheCodeSpec`.
///
/// ## The tests use only the public types
///
/// The Spock scenarios are in package `oillamp`, outside this one, so they can only call
/// [dev.oillamp.OilLamp#run(java.lang.String...)] and check events, problems and exit codes,
/// as any other caller would.
///
/// ## Deciding and doing
///
/// Most classes only decide: they take values and return values, with no file access,
/// processes, clock or randomness. Only the classes on the allowlist in
/// `TheShapeOfTheCodeSpec` actually do things. The phases gather facts, pass them to a pure
/// planner, and hand the resulting plan to [dev.oillamp.StepRunner]. A dry run builds the same
/// plan and only prints it, so what `--dry-run` shows is what a real run would do.
///
/// ## Values and places
///
/// A value is a fact that never changes, such as a record describing what `podman info` said. A
/// place is something whose content changes over time: a file, a socket path, a container, a
/// field. The code decides with values and keeps its places few:
///
/// - Data is immutable records, validated in their constructors. Collections in them are Sprouts
///   persistent collections, where adding returns a new collection. There is no `null`.
/// - Starting up, places are read once into fact records ([dev.oillamp.HostFacts],
///   [dev.oillamp.LampState], [dev.oillamp.LampConfig]), a pure planner turns them into a
///   [dev.oillamp.Plan], and only [dev.oillamp.StepRunner] writes to places.
/// - During a session, everything that happens becomes a [dev.oillamp.SessionEvent] on one queue.
///   [dev.oillamp.SessionMachine] turns state and event into a new state and a list of
///   [dev.oillamp.SessionAction]s, all values. The one field that changes,
///   `Supervisor.state`, has one writer: the event loop.
///
/// The places themselves, with who writes them and how long they last:
///
/// - the lamp directory: configuration, identity, lock, keys, session files, the socket
///   directories the container uses, recordings, the network log and the agent's home
///   ([dev.oillamp.LampLayout]);
/// - the runtime directory `$XDG_RUNTIME_DIR/oillamp/<agent id>/`, outside the lamp and in memory:
///   the host-only sockets and a short symlink to the lamp's sockets;
/// - podman's storage: the image and, during a session, the container;
/// - the supervisor's memory, and the terminal it reports to. No session log is written.
///
/// When the code needs a fact, it asks the place that is authoritative for it rather than a copy:
/// the lock rather than `session.json`, podman's labels rather than a registry, a connection
/// attempt rather than the existence of a socket file.
///
/// `docs/ARCHITECTURE.md` describes the whole design; its section "Where state lives" lists every
/// place.
package dev.oillamp;
