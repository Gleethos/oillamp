package dev.gui.view;

import dev.gui.model.GeniesState;
import dev.gui.model.Settings;

import sprouts.Tuple;
import sprouts.Val;
import sprouts.Var;
import swingtree.UI;
import swingtree.UIForAnySwing;
import swingtree.layout.FlowCell;

import static swingtree.UI.*;

/// The settings: where the genies' model runs, and how they reach it.
///
/// Every field is a lens onto one value of the [GeniesState]'s [Settings], so the page keeps
/// nothing of its own. The app keeps what is there when the page is left, by Done or otherwise.
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
        // Only what the service now named offered; after the place or address changed, nothing.
        Val<Tuple<String>> offered = state.viewAs(Tuple.classTyped(String.class),
                it -> it.lookUp().isFor(it.settings()) ? it.lookUp().models() : Tuple.of(String.class));
        Val<String> lookUpNote = state.viewAsString(it -> it.lookUp().isFor(it.settings()) ? it.lookUp().note() : "");
        Val<Boolean> isEdenAi = place.viewAs(Boolean.class, it -> it == Settings.Place.EDEN_AI);
        Val<Boolean> isElsewhere = place.viewAs(Boolean.class, it -> it == Settings.Place.ELSEWHERE);
        Val<Boolean> isLocal = place.viewAs(Boolean.class, it -> it == Settings.Place.THIS_MACHINE);
        Val<String> problem = state.viewAsString(it -> it.settingsProblem().orElse(""));
        Val<Boolean> hasProblem = state.viewAs(Boolean.class, it -> it.settingsProblem().isPresent());
        boolean found = state.get().environmentKey().isPresent();
        return
            scrollPane(conf -> conf.fitWidth(true)).group(Skin.PAGE_SCROLL).withEmptyBorder(0)
            .isVisibleIf(visible)
            .withHorizontalScrollBarPolicy(UI.Active.NEVER)
            .withVerticalScrollIncrement(24)
            .add(
                panel().withFlowLayout(UI.HorizontalAlignment.CENTER, 0, 30)
                .withMinSize(0, 0).withPrefSize(PAGE_REFERENCE, 0)
                .withStyle(it -> it.backgroundColor(Palette.TRANSPARENT).padding(0, 16, 0, 16))
                .add(CARD,
                    panel().withFlowLayout(UI.HorizontalAlignment.LEFT, 14, 12).group(Skin.CARD)
                    .withMinSize(0, 0).withPrefSize(CARD_REFERENCE, 0)
                    .add(WHOLE, label("Settings").group(Skin.EMPTY_TITLE))
                    .add(WHOLE, label("Where your genies' model runs, and how they reach it.").group(Skin.EMPTY_TEXT).withMinSize(0, 0))

                    .add(LABEL, label("The model runs"))
                    .add(FIELD,
                        box("fill, wrap 1, ins 0, gap 6")
                        .add("wmin 0", radioButton("at Eden AI", Settings.Place.EDEN_AI, place))
                        .add("growx, wmin 0, gapleft 24", Parts.note("In the EU, with your Eden AI key.", Val.of(true)))
                        .add("wmin 0", radioButton("on a server elsewhere", Settings.Place.ELSEWHERE, place))
                        .add("growx, wmin 0, gapleft 24", Parts.note("A model server of your own on another machine, "
                                + "such as Ollama behind a proxy that asks for a key.", Val.of(true)))
                        .add("wmin 0", radioButton("on this computer", Settings.Place.THIS_MACHINE, place))
                        .add("growx, wmin 0, gapleft 24", Parts.note("A model server such as Ollama, LM Studio or llama.cpp.", Val.of(true))))

                    // ── Eden AI ──
                    .add(LABEL, label("Key").isVisibleIf(isEdenAi))
                    .add(FIELD,
                        box("fill, wrap 1, ins 0, gap 6").isVisibleIf(isEdenAi)
                        .add("wmin 0", radioButton("Use " + Settings.KEY_VARIABLE, Settings.KeySource.ENVIRONMENT, source))
                        .add("growx, wmin 0, gapleft 24", Parts.note(found ? "Found where Genies was started."
                                : "Not set where Genies was started.", Val.of(true)))
                        .add("wmin 0", radioButton("Use this key:", Settings.KeySource.ENTERED, source))
                        .add("growx, wmin 0", passwordField(edenKey).group(Skin.INPUT)
                             .isEnabledIf(source.viewAs(Boolean.class, it -> it == Settings.KeySource.ENTERED)))
                        .add("growx, wmin 0", Parts.note(KEY_STAYS_HERE, Val.of(true))))

                    // ── a model server elsewhere ──
                    .add(LABEL, label("Address").isVisibleIf(isElsewhere))
                    .add(FIELD,
                        box("fill, wrap 1, ins 0, gap 4").isVisibleIf(isElsewhere)
                        .add("growx, wmin 0", textField(remoteAddress).group(Skin.INPUT))
                        .add("growx, wmin 0", Parts.note("Where the server's OpenAI-style API is, such as "
                                + "https://ollama.example.com/v1. It must be https://, because the key goes with every request.", Val.of(true))))
                    .add(LABEL, label("Key").isVisibleIf(isElsewhere))
                    .add(FIELD,
                        box("fill, wrap 1, ins 0, gap 4").isVisibleIf(isElsewhere)
                        .add("growx, wmin 0", passwordField(remoteKey).group(Skin.INPUT))
                        .add("growx, wmin 0", Parts.note("Leave it empty if the server asks for none. " + KEY_STAYS_HERE, Val.of(true))))

                    // ── a model server on this computer ──
                    .add(LABEL, label("Address").isVisibleIf(isLocal))
                    .add(FIELD,
                        box("fill, wrap 1, ins 0, gap 4").isVisibleIf(isLocal)
                        .add("growx, wmin 0", textField(localAddress).group(Skin.INPUT))
                        .add("growx, wmin 0", Parts.note("Where the server's OpenAI-style API is: http://127.0.0.1:11434/v1 for Ollama, "
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
                        .add("growx, wmin 0", Parts.wrapped(lookUpNote, Palette.SUBTEXT,
                             lookUpNote.viewAs(Boolean.class, it -> !it.isEmpty()))))

                    .add(WHOLE, label("Changes take effect when a genie wakes.").group(Skin.META))
                    .add(WHOLE, Parts.wrapped(problem, Palette.TROUBLE, hasProblem))
                    .add(WHOLE, label("✓  Your genies can reach their model.").group(Skin.FINE)
                         .isVisibleIf(hasProblem.viewAs(Boolean.class, it -> !it)))
                    .add(WHOLE,
                        box("fill, ins 0", "[grow, right]")
                        .add(button("Done").group(Skin.FLAME_BUTTON).onClick(it -> actions.settingsDone())))));
    }
}
