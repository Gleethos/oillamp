/**
 * oillamp gives an AI coding agent its own sandboxed Linux machine with a graphical desktop.
 *
 * <h2>Five public types</h2>
 *
 * <p>Exactly five types in this package are {@code public}, and a test enforces that number:
 *
 * <ul>
 *   <li>{@link dev.oillamp.OilLamp}: the entry point, {@code OilLamp.on(machine).run(argv)}</li>
 *   <li>{@link dev.oillamp.Machine}: everything oillamp does to the outside world goes through it</li>
 *   <li>{@link dev.oillamp.LampEvent}: what oillamp reports, as values</li>
 *   <li>{@link dev.oillamp.Problem}: what went wrong, with evidence and fixes</li>
 *   <li>{@link dev.oillamp.ExitStatus}: how the process ended</li>
 * </ul>
 *
 * <p>Everything else is package-private, so that it can be changed without breaking any caller.
 * Java enforces this: a package-private class cannot be used from another package. That is also
 * why all the code is in one package. Sub-packages would need public types to talk to each other,
 * and those would be visible to everyone.
 *
 * <p>A package-private class therefore needs no comment explaining its visibility. The five public
 * types each say why they are public. Before making a sixth type public, read their reasons and
 * update {@code TheShapeOfTheCodeSpec}.
 *
 * <h2>The tests use only the public types</h2>
 *
 * <p>The Spock scenarios are in package {@code oillamp}, outside this one, so they can only call
 * {@link dev.oillamp.OilLamp#run(java.lang.String...)} and check events, problems and exit codes,
 * as any other caller would.
 *
 * <h2>Deciding and doing</h2>
 *
 * <p>Most classes only decide: they take values and return values, with no file access,
 * processes, clock or randomness. Only the classes on the allowlist in
 * {@code TheShapeOfTheCodeSpec} actually do things. The phases gather facts, pass them to a pure
 * planner, and hand the resulting plan to {@link dev.oillamp.StepRunner}. A dry run builds the same
 * plan and only prints it, so what {@code --dry-run} shows is what a real run would do.
 *
 * <p>{@code docs/ARCHITECTURE.md} describes the whole design.
 */
package dev.oillamp;
