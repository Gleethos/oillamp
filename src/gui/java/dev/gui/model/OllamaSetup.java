package dev.gui.model;

import sprouts.Tuple;

/// Ollama on this computer, as Genies found it, and Genies setting it up: the simple way to give
/// the genies a model, which the settings offer first.
///
/// Setting up goes through the steps that are left, in order: install Ollama, start it, download
/// the model, prepare it for genies, and try it. Then the genies use it.
///
/// @param found    what Genies found when it last looked
/// @param wanted   the model the user wants, as Ollama names it, such as `qwen3:8b`; at first the
///                 one Genies suggests for this computer
/// @param step     what Genies does now
/// @param progress how far the step is, from 0 to 1
/// @param says     what happens, in a few words, such as how far a download is, or why a step failed
public record OllamaSetup(Found found, String wanted, Step step, double progress, String says) {

    /// What Genies found on this computer.
    ///
    /// @param installed where the `ollama` program is, or empty when it is not installed
    /// @param version   the version of the Ollama answering on this computer, or empty when none answers
    /// @param models    the models that Ollama has, by name
    /// @param hardware  what the computer has for running a model
    public record Found(String installed, String version, Tuple<String> models, Hardware hardware) {
        public static final Found NOTHING = new Found("", "", Tuple.of(String.class), Hardware.UNKNOWN);

        public boolean isInstalled() { return !installed.isEmpty(); }

        public boolean isRunning() { return !version.isEmpty(); }

        /// Whether Ollama has `model`. A name without a tag is the model's `latest`.
        public boolean has(String model) {
            String name = model.strip();
            return models.contains(name) || (!name.contains(":") && models.contains(name + ":latest"));
        }
    }

    public enum Step {
        /// Genies looks at what the computer has, as it does when it starts.
        LOOKING,
        /// Nothing happens.
        IDLE,
        INSTALLING, STARTING, DOWNLOADING, PREPARING, TRYING,
        /// The last step failed, and [#says] why.
        FAILED;

        /// Whether Genies is setting Ollama up.
        public boolean isBusy() { return this != LOOKING && this != IDLE && this != FAILED; }
    }

    /// The genies use the model `m` as `genies/m`: Ollama's model with a context large enough
    /// for a genie, which Genies makes from it.
    public static final String PREFIX = "genies/";

    public static final OllamaSetup NOT_YET = new OllamaSetup(Found.NOTHING, "", Step.LOOKING, 0, "");

    public OllamaSetup withWanted(String wanted) { return new OllamaSetup(found, wanted, step, progress, says); }

    /// At `step`, `progress` of the way through it.
    public OllamaSetup at(Step step, double progress, String says) { return new OllamaSetup(found, wanted, step, progress, says); }

    /// What Genies `found`. Unless the user chose a model already, the one the genies use is
    /// wanted, if they use Ollama; otherwise the one Genies suggests.
    public OllamaSetup found(Found found, Settings settings) {
        String inUse = settings.usesOllama() ? settings.local().model().strip() : "";
        String chosen = !wanted.isBlank() ? wanted
                      : !inUse.isEmpty() ? inUse.substring(inUse.startsWith(PREFIX) ? PREFIX.length() : 0)
                      : found.hardware().suggested();
        return new OllamaSetup(found, chosen, step == Step.LOOKING ? Step.IDLE : step, progress, says);
    }

    /// The models to choose from: those Ollama has, then those Genies suggests. Genies' own
    /// versions of them are not among them, but the models they are made from are.
    public Tuple<String> offered() {
        Tuple<String> had = found.models().removeIf(model -> model.startsWith(PREFIX));
        return had.addAll(Hardware.suggestions().removeIf(had::contains));
    }

    /// Whether the genies use [#wanted], as Genies set it up.
    public boolean inUse(Settings settings) {
        return settings.usesOllama() && settings.local().model().strip().equals(PREFIX + wanted.strip());
    }

    /// What setting up does, as the button that starts it says it.
    public String todo() {
        String model = wanted.strip();
        return !found.isInstalled() && !found.isRunning() ? "Install Ollama and get " + model
             : !found.has(model) ? "Get " + model
             : "Use " + model;
    }

    /// Ollama, in a sentence: whether it is installed, and whether it runs.
    public String words() {
        return step == Step.LOOKING ? "Genies is looking whether Ollama is on this computer…"
             : found.isRunning() ? "Ollama " + found.version() + " runs on this computer."
             : found.isInstalled() ? "Ollama is installed; Genies starts it when the genies need it."
             : "Ollama is not installed yet. Genies installs it for you, for you alone: no password, nothing changes outside its own folder. It is a download of about 1.4 GB.";
    }
}
