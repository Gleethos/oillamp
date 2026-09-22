package dev.oillamp;

import sprouts.Tuple;

/**
 * The ordered changes one startup phase intends to make — spec §10.5.
 *
 * <p>Startup is four rounds of <em>probe facts → plan (pure) → execute plan</em>. Re-probing
 * between rounds is required because earlier rounds change the host: installing podman changes
 * what the next probe sees.
 *
 * <p>Deliberately <b>package-private</b>: an ordered list of steps. Users see plans as {@code
 * --dry-run} text, and that text is the contract.
 */
record Plan(LampEvent.Phase phase, Tuple<Step> steps) {

    public static Plan of(LampEvent.Phase phase, Tuple<Step> steps) { return new Plan(phase, steps); }

    public static Plan nothingToDo(LampEvent.Phase phase) { return new Plan(phase, Tuple.of(Step.class)); }

    public boolean isEmpty() { return steps.isEmpty(); }

    public Plan then(Step step) { return new Plan(phase, steps.add(step)); }

    public Plan thenAll(Tuple<Step> more) { return new Plan(phase, steps.addAll(more)); }

    /** The {@code --dry-run} rendering: one line per step, in execution order. */
    public String describe() {
        StringBuilder out = new StringBuilder();
        for (Step step : steps) out.append(out.isEmpty() ? "" : "\n").append(step.describe());
        return out.toString();
    }
}
