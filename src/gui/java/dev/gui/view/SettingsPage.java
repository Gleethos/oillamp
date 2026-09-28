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
/// nothing of its own; Done asks the app to keep what is there.
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

    private static final Tuple<String> MODELS = Tuple.of(String.class,
            "mistral/mistral-small-latest", "mistral/mistral-medium-latest", "mistral/mistral-large-latest");

    static UIForAnySwing<?, ?> of(Var<GeniesState> state, Actions actions, Val<Boolean> visible) {
        Var<Settings> settings = state.zoomTo(GeniesState::settings, GeniesState::withSettings);
        // Lenses all the way down: settings → the hosted service → its key, and so on. Each field
        // edits one value of the one GeniesState, and nothing else.
        Var<Settings.Place> place = settings.zoomTo(Settings::place, Settings::withPlace);
        Var<Settings.Hosted> hosted = settings.zoomTo(Settings::hosted, Settings::withHosted);
        Var<Settings.OnThisMachine> local = settings.zoomTo(Settings::local, Settings::withLocal);
        Var<String> service = hosted.zoomTo(Settings.Hosted::service, Settings.Hosted::withService);
        Var<Settings.KeySource> source = hosted.zoomTo(Settings.Hosted::keySource, Settings.Hosted::withKeySource);
        Var<String> key = hosted.zoomTo(Settings.Hosted::key, Settings.Hosted::withKey);
        Var<String> hostedModel = hosted.zoomTo(Settings.Hosted::model, Settings.Hosted::withModel);
        Var<String> address = local.zoomTo(Settings.OnThisMachine::address, Settings.OnThisMachine::withAddress);
        Var<String> localModel = local.zoomTo(Settings.OnThisMachine::model, Settings.OnThisMachine::withModel);
        Val<Tuple<String>> offered = state.viewAs(Tuple.classTyped(String.class), it -> it.lookUp().models());
        Val<String> lookUpNote = state.viewAsString(it -> it.lookUp().note());
        Val<Boolean> isHosted = place.viewAs(Boolean.class, it -> it == Settings.Place.HOSTED);
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
                        .add("wmin 0", radioButton("at a hosted service (Eden AI in the EU by default)",
                                Settings.Place.HOSTED, place))
                        .add("wmin 0", radioButton("on this computer (Ollama, LM Studio, llama.cpp)",
                                Settings.Place.THIS_MACHINE, place)))

                    // ── a hosted service ──
                    .add(LABEL, label("Service").isVisibleIf(isHosted))
                    .add(FIELD,
                        box("fill, wrap 1, ins 0, gap 4").isVisibleIf(isHosted)
                        .add("growx, wmin 0", textField(service).group(Skin.INPUT))
                        .add("growx, wmin 0", Parts.note("An OpenAI-style API under /v3, like Eden AI's, or under the path you "
                                + "give. The default keeps requests in the EU.", Val.of(true))))
                    .add(LABEL, label("Key").isVisibleIf(isHosted))
                    .add(FIELD,
                        box("fill, wrap 1, ins 0, gap 6").isVisibleIf(isHosted)
                        .add("wmin 0", radioButton("Use " + Settings.KEY_VARIABLE + " from the environment ("
                                + (found ? "found" : "not set") + ")", Settings.KeySource.ENVIRONMENT, source))
                        .add("wmin 0", radioButton("Use this key:", Settings.KeySource.ENTERED, source))
                        .add("growx, wmin 0", passwordField(key).group(Skin.INPUT)
                             .isEnabledIf(source.viewAs(Boolean.class, it -> it == Settings.KeySource.ENTERED)))
                        .add("growx, wmin 0", Parts.note("The key stays on this computer. Genies never see it: oillamp adds it to "
                                + "their requests as they leave the sandbox. An entered key is kept in a file only you can read.", Val.of(true))))
                    .add(LABEL, label("Model").isVisibleIf(isHosted))
                    .add(FIELD,
                        box("fill, ins 0").isVisibleIf(isHosted)
                        .add("growx, wmin 0", comboBox(hostedModel, MODELS).isEditableIf(true)))

                    // ── a model server on this computer ──
                    .add(LABEL, label("Address").isVisibleIf(isLocal))
                    .add(FIELD,
                        box("fill, wrap 1, ins 0, gap 4").isVisibleIf(isLocal)
                        .add("growx, wmin 0", textField(address).group(Skin.INPUT))
                        .add("growx, wmin 0", Parts.note("Where the server's OpenAI-style API is: http://127.0.0.1:11434/v1 for Ollama, "
                                + "http://127.0.0.1:1234/v1 for LM Studio, http://127.0.0.1:8080/v1 for llama.cpp. No key is needed. "
                                + "Genies reach it only through oillamp, and are offered every model it has.", Val.of(true))))
                    .add(LABEL, label("Model").isVisibleIf(isLocal))
                    .add(FIELD,
                        box("fill, wrap 1, ins 0, gap 4, hidemode 3").isVisibleIf(isLocal)
                        .add("growx, wmin 0",
                            box("fill, ins 0, gap 8", "[grow][]")
                            .add("growx, wmin 0", comboBox(localModel, offered).isEditableIf(true))
                            .add(button("Look up").group(Skin.QUIET_BUTTON)
                                 .withTooltip("Ask the model server which models it has")
                                 .onClick(it -> actions.lookUpModels())))
                        .add("growx, wmin 0", label(lookUpNote).group(Skin.META)
                             .isVisibleIf(lookUpNote.viewAs(Boolean.class, it -> !it.isEmpty()))))

                    .add(WHOLE, label("Changes take effect when a genie wakes.").group(Skin.META))
                    .add(WHOLE, label(problem).group(Skin.PROBLEM).isVisibleIf(hasProblem).withMinSize(0, 0))
                    .add(WHOLE, label("✓  Your genies can reach their model.").group(Skin.FINE)
                         .isVisibleIf(hasProblem.viewAs(Boolean.class, it -> !it)))
                    .add(WHOLE,
                        box("fill, ins 0", "[grow, right]")
                        .add(button("Done").group(Skin.FLAME_BUTTON).onClick(it -> actions.settingsDone())))));
    }
}
