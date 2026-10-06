package dev.gui.view;

import java.awt.Color;
import java.awt.geom.RoundRectangle2D;
import java.util.EnumSet;
import java.util.concurrent.TimeUnit;

import dev.gui.model.Genie;
import dev.gui.model.GeniesState;
import dev.gui.model.OllamaSetup;
import dev.gui.model.Settings;

import sprouts.From;
import sprouts.Tuple;
import sprouts.Val;
import sprouts.Var;
import sprouts.Viewable;
import swingtree.UI;
import swingtree.UIForAnySwing;
import swingtree.animation.Animation;
import swingtree.animation.AnimationStatus;
import swingtree.animation.LifeTime;
import swingtree.layout.FlowCell;
import swingtree.style.StyledString;

import static dev.gui.view.Palette.*;
import static swingtree.UI.*;

/// The settings: where the genies' model runs, and how they reach it.
///
/// They offer two ways to a model. The simple one comes first: Genies sets up Ollama on this
/// computer, with a model suggested for it. The advanced one is folded away: Eden AI, a model
/// server elsewhere, or any model server on this computer.
///
/// Without genies, the page is the first thing the user sees, and welcomes them: the lamp lights,
/// Pip rises from it and waves, and a few lines say what genies are and what they need.
///
/// Every field is a lens onto one value of the [GeniesState], so the page keeps nothing of its
/// own but its welcome's animation. The app keeps the settings when the page is left, by Done or
/// otherwise.
final class SettingsPage {

    private SettingsPage() {}

    /*
     *  A form on a responsive grid, as a pair of cells per setting, a label and its field:
     *
     *                 very small  small  medium  large  very large  oversize
     *      label           12       12     12     12        4          3
     *      field           12       12     12     12        8          9
     *
     *  So in a wide card the labels stand left of their fields, and in a narrow one above them,
     *  where a long label is never cut short. The
     *  card itself is a cell of the page's grid, narrower on a wide page so lines stay short.
     */
    private static final int PAGE_REFERENCE = 900;
    private static final int CARD_REFERENCE = 640;
    private static final FlowCell CARD = AUTO_SPAN(it -> it.fill(true)
            .verySmall(12).small(12).medium(12).large(10).veryLarge(8).oversize(8));
    private static final FlowCell WHOLE = AUTO_SPAN(it -> it
            .verySmall(12).small(12).medium(12).large(12).veryLarge(12).oversize(12));
    private static final FlowCell LABEL = AUTO_SPAN(it -> it.align(UI.VerticalAlignment.TOP)
            .verySmall(12).small(12).medium(12).large(12).veryLarge(4).oversize(3));
    private static final FlowCell FIELD = AUTO_SPAN(it -> it
            .verySmall(12).small(12).medium(12).large(12).veryLarge(8).oversize(9));

    private static final String KEY_STAYS_HERE = "The key stays on this computer. Genies never see it: oillamp adds it to "
            + "their requests as they leave the sandbox. An entered key is kept in a file only you can read.";

    /// Pip as the welcome shows it: the first of the genies' colours, in a turban and a vest.
    private static final GenieSvgUtil.Appearance PIP = new GenieSvgUtil.Appearance(GenieSvgUtil.COLOURS.getFirst(),
            EnumSet.of(GenieSvgUtil.Accessory.TURBAN, GenieSvgUtil.Accessory.VEST));

    /// How long the welcome's animation waits for the window to open, and how long it plays.
    private static final LifeTime WELCOME = LifeTime.of(0.4, TimeUnit.SECONDS, 3.6, TimeUnit.SECONDS);

    static UIForAnySwing<?, ?> of(Var<GeniesState> state, Actions actions, Val<Boolean> visible) {
        Var<Settings> settings = state.zoomTo(GeniesState::settings, GeniesState::withSettings);
        // Lenses all the way down: settings → Eden AI → its key, and so on. Each field
        // edits one value of the one GeniesState, and nothing else.
        Var<Settings.Place> place = settings.zoomTo(Settings::place, Settings::withPlace);
        Var<Settings.EdenAi> edenAi = settings.zoomTo(Settings::edenAi, Settings::withEdenAi);
        Var<Settings.Elsewhere> elsewhere = settings.zoomTo(Settings::elsewhere, Settings::withElsewhere);
        Var<Settings.OnThisMachine> local = settings.zoomTo(Settings::local, Settings::withLocal);
        Var<Settings.KeySource> source = edenAi.zoomTo(Settings.EdenAi::keySource, Settings.EdenAi::withKeySource);
        Var<String> edenKey = edenAi.zoomTo(Settings.EdenAi::key, Settings.EdenAi::withKey);
        Var<String> remoteAddress = elsewhere.zoomTo(Settings.Elsewhere::address, Settings.Elsewhere::withAddress);
        Var<String> remoteKey = elsewhere.zoomTo(Settings.Elsewhere::key, Settings.Elsewhere::withKey);
        Var<String> localAddress = local.zoomTo(Settings.OnThisMachine::address, Settings.OnThisMachine::withAddress);
        // The model of whichever place is chosen, so one field serves all three.
        Var<String> model = settings.zoomTo(Settings::model, Settings::withModel);
        Var<Boolean> advanced = state.zoomTo(GeniesState::advanced, GeniesState::withAdvanced);
        Var<OllamaSetup> ollama = state.zoomTo(GeniesState::ollama, GeniesState::withOllama);
        Var<String> wanted = ollama.zoomTo(OllamaSetup::wanted, OllamaSetup::withWanted);
        // Only what the service now named offered; after the place or address changed, nothing.
        Val<Tuple<String>> offered = state.viewAs(Tuple.classTyped(String.class),
                it -> it.lookUp().isFor(it.settings()) ? it.lookUp().models() : Tuple.of(String.class));
        Val<String> lookUpNote = state.viewAsString(it -> it.lookUp().isFor(it.settings()) ? it.lookUp().note() : "");
        Val<Boolean> simple = advanced.viewAs(Boolean.class, it -> !it);
        Val<Boolean> isEdenAi = state.viewAs(Boolean.class, it -> it.advanced() && it.settings().place() == Settings.Place.EDEN_AI);
        Val<Boolean> isElsewhere = state.viewAs(Boolean.class, it -> it.advanced() && it.settings().place() == Settings.Place.ELSEWHERE);
        Val<Boolean> isLocal = state.viewAs(Boolean.class, it -> it.advanced() && it.settings().place() == Settings.Place.THIS_MACHINE);
        Val<String> problem = state.viewAsString(it -> it.settingsProblem().orElse(""));
        Val<Boolean> hasProblem = state.viewAs(Boolean.class, it -> it.advanced() && it.settingsProblem().isPresent());
        Val<Boolean> fine = state.viewAs(Boolean.class, it -> it.advanced() && it.settingsProblem().isEmpty());
        Val<Boolean> welcome = state.viewAs(Boolean.class, it -> !it.hasGenies());
        Val<Boolean> busy = ollama.viewAs(Boolean.class, it -> it.step().isBusy());
        Val<Boolean> inUse = state.viewAs(Boolean.class, it -> it.ollama().inUse(it.settings()));
        boolean found = state.get().environmentKey().isPresent();
        // The welcome plays when Genies starts without genies, and again when the last is deleted.
        Var<Double> intro = Var.of(welcome.get() ? 0.0 : 1.0);
        if (welcome.get()) welcome(intro);
        Viewable.cast(welcome).onChange(From.ALL, it -> {
            if (it.currentValue().orElse(false)) welcome(intro);
        });
        return
            scrollPane(conf -> conf.fitWidth(true)).group(Skin.PAGE_SCROLL).withEmptyBorder(0)
            .isVisibleIf(visible)
            .withHorizontalScrollBarPolicy(UI.Active.NEVER)
            .withVerticalScrollIncrement(24)
            .add(
                panel().withFlowLayout(UI.HorizontalAlignment.CENTER, 0, 30)
                .withMinSize(0, 0).withPrefSize(PAGE_REFERENCE, 0)
                .withStyle(it -> it.backgroundColor(Palette.TRANSPARENT).padding(0, 16, 0, 16))
                .add(CARD, welcomeAbove(state, intro, welcome))
                .add(CARD,
                    panel().withFlowLayout(UI.HorizontalAlignment.LEFT, 14, 12).group(Skin.CARD)
                    .withMinSize(0, 0).withPrefSize(CARD_REFERENCE, 0)
                    // Without genies, the card comes after the welcome has had its moment.
                    .isVisibleIf(Viewable.of(Boolean.class, welcome, intro, (first, at) -> !first || at >= 0.8))
                    .add(WHOLE, label(welcome.viewAsString(it -> it ? "Your genies' model" : "Settings")).group(Skin.EMPTY_TITLE))
                    .add(WHOLE, ViewPartsUtil.wrapped(advanced.viewAsString(it -> it
                            ? "Where your genies' model runs, and how they reach it."
                            : "Genies sets up a model on this computer for you, with Ollama: free, and your conversations stay here."),
                            SUBTEXT, Val.of(true)))

                    // ── the simple way: Ollama, set up by Genies ──
                    .add(LABEL, label("This computer").isVisibleIf(simple))
                    .add(FIELD, ViewPartsUtil.wrapped(ollama.viewAsString(it -> it.step() == OllamaSetup.Step.LOOKING
                            ? "Genies is looking what this computer has…" : it.found().hardware().words()), TEXT, simple))
                    .add(LABEL, label("Ollama").isVisibleIf(simple))
                    .add(FIELD, ViewPartsUtil.wrapped(ollama.viewAsString(OllamaSetup::words), TEXT, simple))
                    .add(LABEL, label("Model").isVisibleIf(simple))
                    .add(FIELD,
                        box("fill, wrap 1, ins 0, gap 4, hidemode 3").isVisibleIf(simple)
                        .add("growx, wmin 0", comboBox(wanted, ollama.viewAs(Tuple.classTyped(String.class), OllamaSetup::offered))
                             .isEditableIf(true).isEnabledIf(busy.viewAs(Boolean.class, it -> !it)))
                        .add("growx, wmin 0", ViewPartsUtil.wrapped(ollama.viewAsString(it -> it.found().hardware().words(it.wanted())), TEXT,
                             ollama.viewAs(Boolean.class, it -> it.step() != OllamaSetup.Step.LOOKING && !it.wanted().isBlank())))
                        .add("growx, wmin 0", ViewPartsUtil.note("Genies suggests the best model this computer runs well. Any other "
                                + "Ollama model that can use tools works too: ollama.com/search?c=tools lists them.", Val.of(true))))
                    .add(LABEL, label("").isVisibleIf(simple))
                    .add(FIELD,
                        box("fill, wrap 1, ins 0, gap 6, hidemode 3").isVisibleIf(simple)
                        .add(button(ollama.viewAsString(OllamaSetup::todo)).group(Skin.FLAME_BUTTON)
                             .isVisibleIf(state.viewAs(Boolean.class, it -> !it.ollama().step().isBusy() && it.ollama().step() != OllamaSetup.Step.LOOKING
                                                                         && !it.ollama().inUse(it.settings()) && !it.ollama().wanted().isBlank()))
                             .onClick(it -> actions.setUpOllama()))
                        .add("growx, wmin 0", label(ollama.viewAsString(it -> "✓  Your genies use " + it.wanted().strip() + "."))
                             .group(Skin.FINE).isVisibleIf(Viewable.of(Boolean.class, inUse, busy, (yes, working) -> yes && !working)))
                        .add("growx, wmin 0",
                            box("fill, ins 0, gap 10", "[grow][]").isVisibleIf(busy)
                            .add("growx, wmin 60, h 6!", progress(ollama.viewAs(Double.class, OllamaSetup::progress)))
                            .add(button("Stop").group(Skin.QUIET_BUTTON)
                                 .withTooltip("Stop setting up. A model's download goes on from where it was the next time")
                                 .onClick(it -> actions.stopSettingUp())))
                        .add("growx, wmin 0", ViewPartsUtil.wrapped(ollama.viewAsString(OllamaSetup::says),
                             SUBTEXT, ollama.viewAs(Boolean.class, it -> it.step() != OllamaSetup.Step.FAILED && !it.says().isEmpty())))
                        .add("growx, wmin 0", ViewPartsUtil.wrapped(ollama.viewAsString(OllamaSetup::says),
                             TROUBLE, ollama.viewAs(Boolean.class, it -> it.step() == OllamaSetup.Step.FAILED)))
                        .add("growx, wmin 0", ViewPartsUtil.wrapped(settings.viewAsString(it -> "Until then, your genies use " + switch (it.place()) {
                                 case EDEN_AI -> "Eden AI.";
                                 case ELSEWHERE -> "the model server at " + it.elsewhere().address().strip() + ".";
                                 case THIS_MACHINE -> "the model server at " + it.local().address().strip() + ".";
                             }), SUBTEXT, state.viewAs(Boolean.class, it -> !it.settings().usesOllama() && it.settingsProblem().isEmpty()))))

                    // In a box, which holds them to the left: a cell of the grid centres a button.
                    .add(WHOLE,
                        box("ins 0, hidemode 3")
                        .add(ViewPartsUtil.link("Advanced: Eden AI, or a model server of your own  ›").isVisibleIf(simple)
                             .withTooltip("For a model elsewhere, or a model server on this computer other than Ollama")
                             .onClick(it -> advanced.set(From.VIEW, true)))
                        .add(ViewPartsUtil.link("‹  Back to the simple way: Ollama, set up by Genies").isVisibleIf(advanced)
                             .onClick(it -> advanced.set(From.VIEW, false))))

                    // ── the advanced way: where the model runs ──
                    .add(LABEL, label("The model runs").isVisibleIf(advanced))
                    .add(FIELD,
                        box("fill, wrap 1, ins 0, gap 6").isVisibleIf(advanced)
                        .add("wmin 0", radioButton("at Eden AI", Settings.Place.EDEN_AI, place))
                        .add("growx, wmin 0, gapleft 24", ViewPartsUtil.note("In the EU, with your Eden AI key.", Val.of(true)))
                        .add("wmin 0", radioButton("on a server elsewhere", Settings.Place.ELSEWHERE, place))
                        .add("growx, wmin 0, gapleft 24", ViewPartsUtil.note("A model server of your own on another machine, "
                                + "such as Ollama behind a proxy that asks for a key.", Val.of(true)))
                        .add("wmin 0", radioButton("on this computer", Settings.Place.THIS_MACHINE, place))
                        .add("growx, wmin 0, gapleft 24", ViewPartsUtil.note("A model server such as Ollama, LM Studio or llama.cpp.", Val.of(true))))

                    // A line across the card: below it are the settings of the place chosen above.
                    .add(WHOLE, box().isVisibleIf(advanced).withStyle(it -> it.borderAt(UI.Edge.TOP, 1, Palette.BORDER)))

                    // ── Eden AI ──
                    .add(LABEL, label("Key").isVisibleIf(isEdenAi))
                    .add(FIELD,
                        box("fill, wrap 1, ins 0, gap 6").isVisibleIf(isEdenAi)
                        .add("wmin 0", radioButton("Use " + Settings.KEY_VARIABLE, Settings.KeySource.ENVIRONMENT, source))
                        .add("growx, wmin 0, gapleft 24", ViewPartsUtil.note(found ? "Found where Genies was started."
                                : "Not set where Genies was started.", Val.of(true)))
                        .add("wmin 0", radioButton("Use this key:", Settings.KeySource.ENTERED, source))
                        .add("growx, wmin 0", passwordField(edenKey).group(Skin.INPUT)
                             .isEnabledIf(source.viewAs(Boolean.class, it -> it == Settings.KeySource.ENTERED)))
                        .add("growx, wmin 0", ViewPartsUtil.note(KEY_STAYS_HERE, Val.of(true))))

                    // ── a model server elsewhere ──
                    .add(LABEL, label("Address").isVisibleIf(isElsewhere))
                    .add(FIELD,
                        box("fill, wrap 1, ins 0, gap 4").isVisibleIf(isElsewhere)
                        .add("growx, wmin 0", textField(remoteAddress).group(Skin.INPUT))
                        .add("growx, wmin 0", ViewPartsUtil.note("Where the server's OpenAI-style API is, such as "
                                + "https://ollama.example.com/v1. It must be https://, because the key goes with every request.", Val.of(true))))
                    .add(LABEL, label("Key").isVisibleIf(isElsewhere))
                    .add(FIELD,
                        box("fill, wrap 1, ins 0, gap 4").isVisibleIf(isElsewhere)
                        .add("growx, wmin 0", passwordField(remoteKey).group(Skin.INPUT))
                        .add("growx, wmin 0", ViewPartsUtil.note("Leave it empty if the server asks for none. " + KEY_STAYS_HERE, Val.of(true))))

                    // ── a model server on this computer ──
                    .add(LABEL, label("Address").isVisibleIf(isLocal))
                    .add(FIELD,
                        box("fill, wrap 1, ins 0, gap 4").isVisibleIf(isLocal)
                        .add("growx, wmin 0", textField(localAddress).group(Skin.INPUT))
                        .add("growx, wmin 0", ViewPartsUtil.note("Where the server's OpenAI-style API is: http://127.0.0.1:11434/v1 for Ollama, "
                                + "http://127.0.0.1:1234/v1 for LM Studio, http://127.0.0.1:8080/v1 for llama.cpp. No key is needed.", Val.of(true))))

                    // ── the model, wherever it runs ──
                    .add(LABEL, label("Model").isVisibleIf(advanced))
                    .add(FIELD,
                        box("fill, wrap 1, ins 0, gap 4, hidemode 3").isVisibleIf(advanced)
                        .add("growx, wmin 0",
                            box("fill, ins 0, gap 8", "[grow][]")
                            .add("growx, wmin 0", comboBox(model, offered).isEditableIf(true))
                            .add(button("Look up").group(Skin.QUIET_BUTTON)
                                 .withTooltip("Ask the service which models it offers")
                                 .onClick(it -> actions.lookUpModels())))
                        .add("growx, wmin 0", ViewPartsUtil.wrapped(lookUpNote, Palette.SUBTEXT,
                             lookUpNote.viewAs(Boolean.class, it -> !it.isEmpty()))))

                    .add(WHOLE, label("Changes take effect when a genie wakes.").group(Skin.META).isVisibleIf(welcome.viewAs(Boolean.class, it -> !it)))
                    .add(WHOLE, ViewPartsUtil.wrapped(problem, Palette.TROUBLE, hasProblem))
                    .add(WHOLE, label("✓  Your genies can reach their model.").group(Skin.FINE).isVisibleIf(fine))
                    .add(WHOLE,
                        box("fill, ins 0, hidemode 3", "[grow, right]")
                        .add(button("Done").group(Skin.FLAME_BUTTON).isVisibleIf(welcome.viewAs(Boolean.class, it -> !it))
                             .onClick(it -> actions.settingsDone()))
                        .add(button("+  Your first genie").group(Skin.FLAME_BUTTON)
                             .withTooltip("A genie, with a sandboxed desktop of its own, that wakes at once")
                             .isVisibleIf(state.viewAs(Boolean.class, it -> !it.hasGenies() && it.settingsProblem().isEmpty() && !it.ollama().step().isBusy()))
                             .onClick(it -> actions.newGenie())))));
    }

    /*
     *  The welcome, above the settings while there are no genies. Its parts appear one after the
     *  other as the animation runs from 0 to 1:
     *
     *      0    – 0.15   the lamp fades in, cold, with a wisp of smoke
     *      0.15 – 0.25   its flame catches
     *      0.28 – 0.55   Pip rises from the flame
     *      0.55 – 0.75   Pip waves
     *      0.6  – 0.85   the words fade in
     *      0.8           the settings' card appears below
     */
    private static UIForAnySwing<?, ?> welcomeAbove(Var<GeniesState> state, Val<Double> intro, Val<Boolean> welcome) {
        Val<String> foundKey = state.viewAsString(it -> it.environmentKey().isPresent() && it.settings().place() == Settings.Place.EDEN_AI
                ? "Genies found an Eden AI key where it was started, so your genies can think at Eden AI right away. "
                  + "Or have Genies set up a model on this computer instead: the simple way, below."
                : "To think, genies need a model. Genies can set one up for you, right here on this computer.");
        return
            box("fill, wrap 1, ins 10 0 0 0, gap 12", "[grow, center]").isVisibleIf(welcome)
            .add("w 220!, h 200!",
                box().withStyle(intro, (at, it) -> {
                    double lamp = Math.min(1, at / 0.15);
                    Genie.Phase flame = at < 0.15 ? Genie.Phase.ASLEEP : at < 0.25 ? Genie.Phase.WAKING : Genie.Phase.READY;
                    double rise = Math.max(0, Math.min(1, (at - 0.28) / 0.27));
                    double eased = 1 - Math.pow(1 - rise, 3);
                    boolean waving = at >= 0.55 && at < 0.75;
                    GenieSvgUtil.Pose pose = waving ? GenieSvgUtil.Pose.WORKING : GenieSvgUtil.Pose.AWAKE;
                    int frame = waving ? (int) ((at - 0.55) / 0.05) % 2 : 0;
                    return it
                        .image(UI.Layer.BACKGROUND, "lamp", img -> img.svg(LampSvgUtil.lamp(flame))
                               .placement(UI.Placement.BOTTOM).size(130, 130).opacity((float) lamp))
                        .image(UI.Layer.CONTENT, "pip", img -> img.svg(GenieSvgUtil.svg(PIP, pose, frame))
                               .placement(UI.Placement.TOP).size(100, 100).offset(0, (int) Math.round(60 * (1 - eased)))
                               .opacity((float) eased));
                }))
            .add("growx, wmin 0", fading(Val.of("Welcome to Genies"), 22, TEXT, intro))
            .add("growx, wmin 0", fading(Val.of("A genie is an AI helper with a computer of its own: a Linux desktop in a sandbox, "
                    + "where it can browse the web, run programs and make files, without ever touching yours."), 14, SUBTEXT, intro))
            .add("growx, wmin 0", fading(foundKey, 14, SUBTEXT, intro));
    }

    /// Lines of `text` that fade in with the welcome, centred, wrapped to their width.
    private static UIForAnySwing<?, ?> fading(Val<String> text, int size, Color colour, Val<Double> intro) {
        return
            box().withMinSize(0, 0)
            .withStyle(Viewable.of(Faded.class, text, intro, (words, at) -> new Faded(words, Math.max(0, Math.min(1, (at - 0.6) / 0.25)))),
                (faded, it) -> it.padding(2, 0, 2, 0).text(t -> t
                    .content(StyledString.of(f -> f.family(FONT).size(size).color(ViewPartsUtil.withAlpha(colour, (int) Math.round(255 * faded.shown())))
                                                   .horizontalAlignment(UI.HorizontalAlignment.CENTER), faded.words()))
                    .placement(UI.Placement.TOP).wrapLines(true).autoPreferredHeight(true)));
    }

    /// Words of the welcome, and how far they faded in, from 0 to 1.
    private record Faded(String words, double shown) {}

    /// Plays the welcome from its start.
    private static void welcome(Var<Double> intro) {
        intro.set(0.0);
        UI.animateFor(WELCOME).go(new Animation() {
            @Override public void run(AnimationStatus status) { intro.set(status.progress()); }
            @Override public void finish(AnimationStatus status) { intro.set(1.0); }
        });
    }

    /// A bar that fills from the left as `done` goes from 0 to 1.
    private static UIForAnySwing<?, ?> progress(Val<Double> done) {
        return
            box().withMinSize(0, 6)
            .withStyle(done, (part, it) -> {
                int length = UI.scale(it.componentWidth());
                int thickness = UI.scale(it.componentHeight());
                return it.backgroundColor(RAISED).borderRadius(3)
                         .painter(UI.Layer.CONTENT, g -> {
                             g.setColor(FLAME);
                             g.fill(new RoundRectangle2D.Float(0, 0, (float) (length * Math.max(0, Math.min(1, part))), thickness, thickness, thickness));
                         });
            });
    }
}
