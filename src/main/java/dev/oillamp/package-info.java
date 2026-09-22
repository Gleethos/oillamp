/**
 * oillamp — gives an AI coding agent its own sandboxed Linux machine with a graphical desktop.
 *
 * <h2>Five public types, and why that is the whole design</h2>
 *
 * <p>Exactly five types in this package are {@code public}, and a test enforces that number:
 *
 * <ul>
 *   <li>{@link dev.oillamp.OilLamp} — the entry point: {@code OilLamp.on(machine).run(argv)}</li>
 *   <li>{@link dev.oillamp.Machine} — the one seam through which effects happen</li>
 *   <li>{@link dev.oillamp.LampEvent} — what is happening, as a stream</li>
 *   <li>{@link dev.oillamp.Problem} — what went wrong, structured</li>
 *   <li>{@link dev.oillamp.ExitStatus} — how the process ended</li>
 * </ul>
 *
 * <p>Everything else is package-private, and that is not an accident of where they happened to be
 * written. The public API is the part that cannot be changed later without breaking somebody, so
 * it is the part worth keeping small — and a small API is only real if something enforces it. Java
 * enforces this one: a package-private class <em>cannot</em> be named from another package. Not by
 * a careless contributor, not by a test in a hurry, not by a future GUI. The rule holds without
 * anybody having to remember it.
 *
 * <p>That is why everything lives in one package. Sub-packages would each need their own public
 * types to talk to one another, and every one of those would be public to the whole world too —
 * Java has no "public within these packages". Splitting for tidiness would quietly triple the
 * surface. One package is the price of keeping every other class genuinely unreachable.
 *
 * <h2>Every class says which it is, and why</h2>
 *
 * <p>Each type in this package carries a line in its Javadoc beginning <b>"Deliberately
 * package-private:"</b> or <b>"Deliberately public:"</b>. A visibility keyword records a decision
 * but not its reason, and the reason is what a later contributor needs — someone widening the
 * surface should have to disagree with a written argument, not merely fail to notice one was ever
 * made. When a class is made public, that line is the thing to rewrite first.
 *
 * <h2>The tests cannot see any of this</h2>
 *
 * <p>The Spock scenarios live in package {@code oillamp}, deliberately <em>outside</em> this one.
 * They therefore cannot reach an internal even if they want to: every scenario has to go through
 * {@link dev.oillamp.OilLamp#run(java.lang.String...)} and assert on events, problems and exit
 * codes. Which means every scenario describes something a user could recognise — that constraint
 * is why they read like <i>"Every missing prerequisite is reported in one run, not one per
 * attempt"</i> instead of {@code testPlannerCombinesProblems()}.
 *
 * <p>{@code TheShapeOfTheCodeSpec} fails the build if a sixth type becomes public, if an internal
 * type appears in a public signature, or if a class outside a named allowlist touches the
 * filesystem, processes, the clock or randomness.
 *
 * <h2>Shape of the code</h2>
 *
 * <p>Functional core, imperative shell. Nearly everything here is a pure function over records:
 * probe the machine into facts, plan from facts into steps, render steps into text. Only the
 * classes on {@code TheShapeOfTheCodeSpec}'s allowlist actually <em>do</em> anything. This is what
 * makes {@code --dry-run} trustworthy — it is the same code path with the execution omitted, so
 * the plan it prints is by construction the plan that would have run.
 *
 * @see <a href="../../../../oillamp-design-spec.md">oillamp-design-spec.md</a> §22
 */
package dev.oillamp;
