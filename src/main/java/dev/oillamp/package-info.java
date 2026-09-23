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
/// `docs/ARCHITECTURE.md` describes the whole design.
package dev.oillamp;
