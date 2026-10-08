package dev.gui.view;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.geom.RoundRectangle2D;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JComponent;

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
/// Pip takes form from its flame ([WelcomeScene]), and a few lines say what genies are and what
/// they need.
///
/// Every field is a lens onto one value of the [GeniesState], so the page keeps nothing of its
/// own but its welcome's animation. The app keeps the settings when the page is left, by Done or
/// otherwise.
final class SettingsPage {

    private SettingsPage() {}

    /*
     *  A form on a responsive grid. The card's settings come in two halves, side by side once
     *  the card is wider than its reference width, one above the other otherwise:
     *
     *                 very small  small  medium  large  very large  oversize
     *      half            12       12     12     12       12          6
     *
     *  Each half is a grid of its own, as a pair of cells per setting, a label and its field:
     *
     *      label           12       12     12     12        4          3
     *      field           12       12     12     12        8          9
     *
     *  So in a wide half the labels stand left of their fields, and in a narrow one above them,
     *  where a long label is never cut short. The card itself is a cell of the page's grid,
     *  narrower on a wide page so lines stay short.
     */
    private static final int PAGE_REFERENCE = 900;
    private static final int CARD_REFERENCE = 760;
    private static final int HALF_REFERENCE = 560;
    private static final FlowCell CARD = AUTO_SPAN(it -> it.fill(true)
            .verySmall(12).small(12).medium(12).large(10).veryLarge(8).oversize(8));
    private static final FlowCell WHOLE = AUTO_SPAN(it -> it
            .verySmall(12).small(12).medium(12).large(12).veryLarge(12).oversize(12));
    private static final FlowCell HALF = AUTO_SPAN(it -> it.align(UI.VerticalAlignment.TOP)
            .verySmall(12).small(12).medium(12).large(12).veryLarge(12).oversize(6));
    private static final FlowCell LABEL = AUTO_SPAN(it -> it.align(UI.VerticalAlignment.TOP)
            .verySmall(12).small(12).medium(12).large(12).veryLarge(4).oversize(3));
    private static final FlowCell FIELD = AUTO_SPAN(it -> it
            .verySmall(12).small(12).medium(12).large(12).veryLarge(8).oversize(9));

    /*
     *  The welcome on a grid of its own, its words and the room for its picture:
     *
     *                 very small  small  medium  large  very large  oversize
     *      words           12       12     12     12        7          7
     *      picture         12       12     12     12        5          5
     *
     *  So in a wide welcome the words stand left of the picture, and in a narrow one below it.
     *  The picture is painted on the whole welcome, so that it can move from the middle to its
     *  room beside the words.
     */
    private static final int WELCOME_REFERENCE = 760;

    /// How much larger the welcome's words grow, at most, as it grows wider than its reference
    /// width: as much larger as it is wider.
    private static final double MOST_LARGER = 1.35;
    private static final int BESIDE_SPAN = 5;
    private static final FlowCell WORDS = AUTO_SPAN(it -> it.align(UI.VerticalAlignment.CENTER)
            .verySmall(12).small(12).medium(12).large(12).veryLarge(12 - BESIDE_SPAN).oversize(12 - BESIDE_SPAN));
    private static final FlowCell BESIDE = AUTO_SPAN(it -> it
            .verySmall(12).small(12).medium(12).large(12).veryLarge(BESIDE_SPAN).oversize(BESIDE_SPAN));

    private static final String KEY_STAYS_HERE = "The key stays on this computer. Genies never see it: oillamp adds it to "
            + "their requests as they leave the sandbox. An entered key is kept in a file only you can read.";

    /// When the welcome's words fade in, and how long they take, in seconds of its play.
    private static final double WORDS_FROM = 7.4;
    private static final double WORDS_TAKE = 0.8;

    /// How tall the welcome's picture is. In a narrow window, the empty top of the picture would
    /// look tall beside its narrow sides, so its top is cut away: none of it while the welcome is
    /// `UNCUT_WIDTH` wide or wider, `PICTURE_CUT` once it is `CUT_WIDTH` or narrower, and in
    /// between, an even share. The lamp and the genie stay their size, at the bottom.
    private static final int PICTURE_HEIGHT = 360;
    private static final int PICTURE_CUT = 100;
    private static final int UNCUT_WIDTH = 760;
    private static final int CUT_WIDTH = 360;

    /// When the settings' card appears below the welcome, in seconds of its play.
    private static final double CARD_AT = 8.2;

    /// In a wide welcome, when the genie, once it has taken form, moves aside, and how long that
    /// takes; then when the words fade in on its left, and how long they take, and when the
    /// settings' card appears, in seconds of its play.
    private static final double ASIDE_FROM = 8.0;
    private static final double ASIDE_TAKES = 1.0;
    private static final double WORDS_BESIDE_FROM = 8.8;
    private static final double WORDS_BESIDE_TAKE = 1.2;
    private static final double CARD_BESIDE_AT = 9.8;

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
        // Whether the card's halves stand side by side.
        Var<Boolean> split = Var.of(false);
        Val<Boolean> busy = ollama.viewAs(Boolean.class, it -> it.step().isBusy());
        Val<Boolean> inUse = state.viewAs(Boolean.class, it -> it.ollama().inUse(it.settings()));
        boolean found = state.get().environmentKey().isPresent();
        // The welcome plays when Genies starts without genies, and again when the last is
        // deleted. Its clock is the seconds since it began, and it runs as long as it is shown.
        // A click on the genie starts another play of its picture, on the same clock.
        Var<Double> clock = Var.of(welcome.get() ? 0.0 : CARD_AT);
        Var<WelcomeScene.Play> scene = Var.of(WelcomeScene.Play.first(0));
        // How much of the welcome's picture is cut from its top, in units, and the welcome
        // itself, so that the page can paint its light where it is.
        Var<Integer> cut = Var.of(0);
        AtomicReference<JComponent> welcomeBox = new AtomicReference<>();
        // How the welcome fits its width, and how far its picture has moved from the middle to
        // its room beside the words, from 0 to 1.
        Var<Fit> fit = Var.of(new Fit(false, 1));
        Var<Double> aside = Var.of(0.0);
        AtomicLong began = new AtomicLong();
        AtomicBoolean playing = new AtomicBoolean();
        if (welcome.get()) play(clock, scene, fit, aside, welcome, began, playing);
        Viewable.cast(welcome).onChange(From.ALL, it -> {
            if (it.currentValue().orElse(false)) play(clock, scene, fit, aside, welcome, began, playing);
        });
        return
            scrollPane(conf -> conf.fitWidth(true)).group(Skin.PAGE_SCROLL).withEmptyBorder(0)
            .isVisibleIf(visible)
            .withHorizontalScrollBarPolicy(UI.Active.NEVER)
            .withVerticalScrollIncrement(24)
            .add(
                panel().withFlowLayout(UI.HorizontalAlignment.CENTER, 0, 30)
                .withMinSize(0, 0).withPrefSize(PAGE_REFERENCE, 0)
                // The welcome's light, on the whole page behind it, so that only the window's
                // edges cut it off.
                .withStyle(clock, (at, it) -> it.backgroundColor(Palette.TRANSPARENT).padding(0, 16, 0, 16)
                    .painter(UI.Layer.BACKGROUND, g -> {
                        JComponent shown = welcomeBox.get();
                        if (shown == null || !shown.isVisible()) return;
                        // SwingTree scales a painter's units to pixels; the welcome paints in pixels.
                        g.scale(1 / UI.scale(), 1 / UI.scale());
                        g.translate(shown.getX() + asideBy(shown.getWidth(), aside.get()), shown.getY());
                        WelcomeScene.light(g, shown.getWidth(), UI.scale(PICTURE_HEIGHT), UI.scale(cut.get()), scene.get(), at);
                    }))
                .add(CARD, welcome(state, clock, scene, cut, fit, aside, welcomeBox, welcome))
                .add(CARD,
                    panel().withFlowLayout(UI.HorizontalAlignment.LEFT, 14, 12).group(Skin.CARD)
                    .withMinSize(0, 0).withPrefSize(CARD_REFERENCE, 0)
                    // Split where the grid puts the halves side by side: past its reference width.
                    .onResize(it -> split.set(it.getComponent().getWidth() > UI.scale(CARD_REFERENCE)))
                    // Without genies, the card comes after the welcome has had its moment.
                    .isVisibleIf(Viewable.of(Boolean.class, welcome, clock, (first, at) -> !first || at >= (fit.get().wide() ? CARD_BESIDE_AT : CARD_AT)))
                    .add(WHOLE, label(welcome.viewAsString(it -> it ? "Your genies' model" : "Settings")).group(Skin.EMPTY_TITLE))
                    .add(WHOLE, ViewPartsUtil.wrapped(advanced.viewAsString(it -> it
                            ? "Where your genies' model runs, and how they reach it."
                            : "Genies sets up a model on this computer for you, with Ollama: free, and your conversations stay here."),
                            SUBTEXT, Val.of(true)))

                    // ── the simple way: Ollama, set up by Genies ──
                    // What this computer has in one half, the model and setting it up in the other.
                    .add(HALF,
                        box().withFlowLayout(UI.HorizontalAlignment.LEFT, 0, 12)
                        .withMinSize(0, 0).withPrefSize(HALF_REFERENCE, 0).isVisibleIf(simple)
                        .add(LABEL, label("This computer"))
                        .add(FIELD, ViewPartsUtil.wrapped(ollama.viewAsString(it -> it.step() == OllamaSetup.Step.LOOKING
                                ? "Genies is looking what this computer has…" : it.found().hardware().words()), TEXT, Val.of(true)))
                        .add(LABEL, label("Ollama"))
                        .add(FIELD, ViewPartsUtil.wrapped(ollama.viewAsString(OllamaSetup::words), TEXT, Val.of(true))))
                    .add(HALF,
                        box().withFlowLayout(UI.HorizontalAlignment.LEFT, 0, 12)
                        .withMinSize(0, 0).withPrefSize(HALF_REFERENCE, 0).isVisibleIf(simple)
                        .add(LABEL, label("Model"))
                        .add(FIELD,
                            box("fill, wrap 1, ins 0, gap 4, hidemode 3")
                            .add("growx, wmin 0", comboBox(wanted, ollama.viewAs(Tuple.classTyped(String.class), OllamaSetup::offered))
                                 .isEditableIf(true).isEnabledIf(busy.viewAs(Boolean.class, it -> !it)))
                            .add("growx, wmin 0", ViewPartsUtil.wrapped(ollama.viewAsString(it -> it.found().hardware().words(it.wanted())), TEXT,
                                 ollama.viewAs(Boolean.class, it -> it.step() != OllamaSetup.Step.LOOKING && !it.wanted().isBlank())))
                            .add("growx, wmin 0", ViewPartsUtil.note("Genies suggests the best model this computer runs well. Any other "
                                    + "Ollama model that can use tools works too: ollama.com/search?c=tools lists them.", Val.of(true))))
                        .add(LABEL, label(""))
                        .add(FIELD,
                            box("fill, wrap 1, ins 0, gap 6, hidemode 3")
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
                                 }), SUBTEXT, state.viewAs(Boolean.class, it -> !it.settings().usesOllama() && it.settingsProblem().isEmpty())))))

                    // In a box, which holds them to the left: a cell of the grid centres a button.
                    .add(WHOLE,
                        box("ins 0, hidemode 3")
                        .add(ViewPartsUtil.link("Advanced: Eden AI, or a model server of your own  ›").isVisibleIf(simple)
                             .withTooltip("For a model elsewhere, or a model server on this computer other than Ollama")
                             .onClick(it -> advanced.set(From.VIEW, true)))
                        .add(ViewPartsUtil.link("‹  Back to the simple way: Ollama, set up by Genies").isVisibleIf(advanced)
                             .onClick(it -> advanced.set(From.VIEW, false))))

                    // ── the advanced way: where the model runs in one half, how to reach it in the other ──
                    .add(HALF,
                        box().withFlowLayout(UI.HorizontalAlignment.LEFT, 0, 12)
                        .withMinSize(0, 0).withPrefSize(HALF_REFERENCE, 0).isVisibleIf(advanced)
                        .add(LABEL, label("The model runs"))
                        .add(FIELD,
                            box("fill, wrap 1, ins 0, gap 6")
                            .add("wmin 0", radioButton("at Eden AI", Settings.Place.EDEN_AI, place))
                            .add("growx, wmin 0, gapleft 24", ViewPartsUtil.note("In the EU, with your Eden AI key.", Val.of(true)))
                            .add("wmin 0", radioButton("on a server elsewhere", Settings.Place.ELSEWHERE, place))
                            .add("growx, wmin 0, gapleft 24", ViewPartsUtil.note("A model server of your own on another machine, "
                                    + "such as Ollama behind a proxy that asks for a key.", Val.of(true)))
                            .add("wmin 0", radioButton("on this computer", Settings.Place.THIS_MACHINE, place))
                            .add("growx, wmin 0, gapleft 24", ViewPartsUtil.note("A model server such as Ollama, LM Studio or llama.cpp.", Val.of(true)))))

                    // One half above the other, a line across the card between them: below it are
                    // the settings of the place chosen above.
                    .add(WHOLE, box().isVisibleIf(Viewable.of(Boolean.class, advanced, split, (shown, beside) -> shown && !beside))
                                     .withStyle(it -> it.borderAt(UI.Edge.TOP, 1, Palette.BORDER)))
                    .add(HALF,
                        box().withFlowLayout(UI.HorizontalAlignment.LEFT, 0, 12)
                        .withMinSize(0, 0).withPrefSize(HALF_REFERENCE, 0).isVisibleIf(advanced)
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
                        .add(LABEL, label("Model"))
                        .add(FIELD,
                            box("fill, wrap 1, ins 0, gap 4, hidemode 3")
                            .add("growx, wmin 0",
                                box("fill, ins 0, gap 8", "[grow][]")
                                .add("growx, wmin 0", comboBox(model, offered).isEditableIf(true))
                                .add(button("Look up").group(Skin.QUIET_BUTTON)
                                     .withTooltip("Ask the service which models it offers")
                                     .onClick(it -> actions.lookUpModels())))
                            .add("growx, wmin 0", ViewPartsUtil.wrapped(lookUpNote, Palette.SUBTEXT,
                                 lookUpNote.viewAs(Boolean.class, it -> !it.isEmpty())))))

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

    /// The welcome, above the settings while there are no genies: its picture, and its words. In
    /// a wide welcome, the genie moves aside once it has taken form, and the words fade in on its
    /// left; in a narrow one, they fade in below it.
    private static UIForAnySwing<?, ?> welcome(Var<GeniesState> state, Val<Double> clock, Var<WelcomeScene.Play> scene, Var<Integer> cut,
                                               Var<Fit> fit, Val<Double> aside, AtomicReference<JComponent> welcomeBox, Val<Boolean> welcome) {
        Val<String> foundKey = state.viewAsString(it -> it.environmentKey().isPresent() && it.settings().place() == Settings.Place.EDEN_AI
                ? "Genies found an Eden AI key where it was started, so your genies can think at Eden AI right away. "
                  + "Or have Genies set up a model on this computer instead: the simple way, below."
                : "To think, genies need a model. Genies can set one up for you, right here on this computer.");
        Val<Integer> room = cut.viewAs(Integer.class, it -> PICTURE_HEIGHT - it);
        return
            panel().withFlowLayout(UI.HorizontalAlignment.CENTER, 0, 12)
            .withMinSize(0, 0).withPrefSize(WELCOME_REFERENCE, 0)
            .isVisibleIf(welcome).peek(welcomeBox::set)
            .onResize(it -> {
                int width = it.getComponent().getWidth();
                double narrowed = (UNCUT_WIDTH - UI.unscale(width)) / (double) (UNCUT_WIDTH - CUT_WIDTH);
                cut.set((int) Math.round(PICTURE_CUT * Math.max(0, Math.min(1, narrowed))));
                // Wide where the grid puts the words beside the picture: from four fifths of its
                // reference width on.
                fit.set(new Fit(5 * width >= 4 * UI.scale(WELCOME_REFERENCE),
                                Math.max(1, Math.min(MOST_LARGER, UI.unscale(width) / (double) WELCOME_REFERENCE))));
            })
            // Painted whole, its cut top above the welcome, and as far aside as it has moved.
            .withStyle(clock, (at, it) -> {
                int width = UI.scale(it.componentWidth());
                return it.backgroundColor(Palette.TRANSPARENT).painter(UI.Layer.CONTENT, g -> {
                    // SwingTree scales a painter's units to pixels; the welcome paints in pixels.
                    g.scale(1 / UI.scale(), 1 / UI.scale());
                    g.translate(asideBy(width, aside.get()), -UI.scale(cut.get()));
                    WelcomeScene.paint(g, width, UI.scale(PICTURE_HEIGHT), scene.get(), at);
                });
            })
            // The genie, once it has taken form, and its lamp can be clicked: it vanishes in a
            // poof, and another genie comes.
            .onMouseMove(it -> {
                int width = it.getComponent().getWidth();
                it.getComponent().setCursor(Cursor.getPredefinedCursor(scene.get().formed(clock.get())
                        && WelcomeScene.hits(width, UI.scale(PICTURE_HEIGHT), it.mouseX() - asideBy(width, aside.get()), it.mouseY() + UI.scale(cut.get()))
                        ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
            })
            .onMouseClick(it -> {
                int width = it.getComponent().getWidth();
                if (scene.get().formed(clock.get())
                        && WelcomeScene.hits(width, UI.scale(PICTURE_HEIGHT), it.mouseX() - asideBy(width, aside.get()), it.mouseY() + UI.scale(cut.get())))
                    scene.set(scene.get().poof(clock.get()));
            })
            // The room for the picture: above the words in a narrow welcome, beside them in a wide one.
            .add(WHOLE, box().withMinSize(0, 0).withHeightExactly(room).isVisibleIf(fit.viewAs(Boolean.class, it -> !it.wide())))
            .add(WORDS,
                box("wrap 1, ins 0, gap 12", "[grow, fill]").withMinSize(0, 0)
                .add("growx, wmin 0", fading(Val.of("Welcome to Genies"), 22, TEXT, clock, fit, aside))
                .add("growx, wmin 0", fading(Val.of("A genie is an AI helper with a computer of its own: a Linux desktop in a sandbox, "
                        + "where it can browse the web, run programs and make files, without ever touching yours."), 14, SUBTEXT, clock, fit, aside))
                .add("growx, wmin 0", fading(foundKey, 14, SUBTEXT, clock, fit, aside)))
            .add(BESIDE, box().withMinSize(0, 0).withHeightExactly(room).isVisibleIf(fit.viewAs(Boolean.class, Fit::wide)));
    }

    /// How many pixels the welcome's picture is right of the middle of a welcome `width` pixels
    /// wide, `aside` of the way to the middle of its room beside the words. It eases in and out.
    private static int asideBy(int width, double aside) {
        double eased = aside * aside * (3 - 2 * aside);
        return (int) Math.round(eased * width * (12 - BESIDE_SPAN) / 24.0);
    }

    /// Lines of `text`, `size` units large in a welcome no wider than its reference width, that
    /// fade in with the welcome's words, wrapped to their width: centred below the picture, or
    /// left-aligned beside it once it has moved aside.
    private static UIForAnySwing<?, ?> fading(Val<String> text, int size, Color colour, Val<Double> clock, Val<Fit> fit, Val<Double> aside) {
        return
            box().withMinSize(0, 0)
            .withStyle(Viewable.of(Faded.class, text, clock, (words, at) -> Faded.of(words, at, fit.get(), aside.get())),
                (faded, it) -> it.padding(2, 0, 2, 0).text(t -> t
                    .content(StyledString.of(f -> f.family(FONT).size((int) Math.round(size * faded.fit().larger()))
                                                   .color(ViewPartsUtil.withAlpha(colour, (int) Math.round(255 * faded.shown())))
                                                   .horizontalAlignment(faded.fit().wide() ? UI.HorizontalAlignment.LEFT : UI.HorizontalAlignment.CENTER),
                                             faded.words()))
                    .placement(faded.fit().wide() ? UI.Placement.TOP_LEFT : UI.Placement.TOP).wrapLines(true).autoPreferredHeight(true)));
    }

    /// How the welcome fits its width: whether its words stand beside its picture, and how many
    /// times larger they are than in a welcome no wider than its reference width.
    private record Fit(boolean wide, double larger) {}

    /// Words of the welcome, how far they faded in, from 0 to 1, and how the welcome fits.
    private record Faded(String words, double shown, Fit fit) {

        /// `words` at `at` seconds of the welcome's play. Beside the picture, they also wait for
        /// it to have moved most of the way aside, however late the welcome grew wide.
        static Faded of(String words, double at, Fit fit, double aside) {
            double shown = fit.wide()
                    ? Math.min((at - WORDS_BESIDE_FROM) / WORDS_BESIDE_TAKE, 2 * aside - 1)
                    : (at - WORDS_FROM) / WORDS_TAKE;
            return new Faded(words, Math.max(0, Math.min(1, shown)), fit);
        }
    }

    /// Plays the welcome from its start, for as long as it is shown. Played again while it plays,
    /// it starts over on the same loop. Its picture moves aside, or back to the middle, as the
    /// welcome grows wide or narrow, at the same pace whenever it does.
    private static void play(Var<Double> clock, Var<WelcomeScene.Play> scene, Val<Fit> fit, Var<Double> aside,
                             Val<Boolean> welcome, AtomicLong began, AtomicBoolean playing) {
        began.set(System.nanoTime());
        clock.set(0.0);
        aside.set(0.0);
        scene.set(WelcomeScene.Play.first(0));
        if (playing.getAndSet(true)) return;
        UI.animateFor(LifeTime.of(1, TimeUnit.SECONDS)).asLongAs(status -> welcome.get()).go(new Animation() {
            @Override public void run(AnimationStatus status) {
                double now = (System.nanoTime() - began.get()) / 1e9;
                double step = Math.max(0, now - clock.get()) / ASIDE_TAKES;
                aside.set(fit.get().wide() && now >= ASIDE_FROM ? Math.min(1, aside.get() + step) : Math.max(0, aside.get() - step));
                clock.set(now);
            }
            @Override public void finish(AnimationStatus status) { playing.set(false); }
        });
    }

    /// A bar that fills from the left as `done` goes from 0 to 1.
    private static UIForAnySwing<?, ?> progress(Val<Double> done) {
        return
            box().withMinSize(0, 6)
            .withStyle(done, (part, it) -> {
                int length = it.componentWidth();
                int thickness = it.componentHeight();
                return it.backgroundColor(RAISED).borderRadius(3)
                         .painter(UI.Layer.CONTENT, g -> {
                             g.setColor(FLAME);
                             g.fill(new RoundRectangle2D.Float(0, 0, (float) (length * Math.max(0, Math.min(1, part))), thickness, thickness, thickness));
                         });
            });
    }
}
