package dev.oillamp;

import sprouts.Tuple;

/// The ordered list of changes one phase intends to make. [StepRunner] carries it out, or,
/// in a dry run, only prints it.
record Plan(LampEvent.Phase phase, Tuple<Step> steps) {

    public static Plan of(LampEvent.Phase phase, Tuple<Step> steps) { return new Plan(phase, steps); }

    public static Plan nothingToDo(LampEvent.Phase phase) { return new Plan(phase, Tuple.of(Step.class)); }

    public boolean isEmpty() { return steps.isEmpty(); }

    public Plan then(Step step) { return new Plan(phase, steps.add(step)); }

    public Plan thenAll(Tuple<Step> more) { return new Plan(phase, steps.addAll(more)); }

    /// One line per step, in the order they would run.
    public String describe() {
        StringBuilder out = new StringBuilder();
        for (Step step : steps) out.append(out.isEmpty() ? "" : "\n").append(step.describe());
        return out.toString();
    }
}
