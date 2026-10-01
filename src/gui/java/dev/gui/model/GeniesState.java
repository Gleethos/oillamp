package dev.gui.model;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

import sprouts.Tuple;

/// Everything the Genies window shows, as one value.
///
/// The window is a function of this value. Every change, whether the user clicked something or
/// a genie said something, is a method that returns a new one, and the window follows.
///
/// @param genies   every genie, in the order they were made
/// @param selected the genie the chat shows. Refers to no genie while there are none
/// @param settings the model settings every genie uses when it wakes
/// @param page     the chat, or the settings
/// @param environmentKey the key in `EDENAI_API_KEY` where Genies started, if any; kept here so
///                 the settings can say whether it is there, and never shown
/// @param sidebarShown whether the list of genies is shown; hidden to make room in a narrow window
/// @param narrow   whether the window is too narrow for the list and a conversation side by side
/// @param lookUp   the models the service in the settings offered when last asked
/// @param zoom     how large a genie's desktop is shown
/// @param area     the room the conversation, and the desktop beside it, have in the window
/// @param now      the time the window shows things relative to, such as the schedule's timeline;
///                 moved on every half minute
public record GeniesState(Tuple<Genie> genies, UUID selected, Settings settings, Page page,
                          Optional<String> environmentKey, boolean sidebarShown, boolean narrow,
                          ModelLookUp lookUp, DesktopZoom zoom, Area area, Instant now) {

    /// A width and a height, in the window's own units.
    public record Area(int width, int height) {}

    /// What asking a model service for its models found.
    ///
    /// @param place   the place in the settings it was asked for
    /// @param service the address it was asked at
    /// @param models  the models it offers, by the names genies use for them, in alphabetical order
    /// @param note    what happened, in a few words, such as how many were found or why none were
    public record ModelLookUp(Settings.Place place, String service, Tuple<String> models, String note) {
        public static final ModelLookUp NOT_YET = new ModelLookUp(Settings.Place.EDEN_AI, "", Tuple.of(String.class), "");

        /// Whether this is the answer for the service the settings name now. After the place or
        /// the address changed, it is not, and the settings do not offer its models.
        public boolean isFor(Settings settings) {
            return place == settings.place() && service.equals(settings.service());
        }
    }

    /// The selected genie's chat, its schedule, or the settings every genie shares.
    public enum Page { CHAT, SCHEDULE, SETTINGS }

    /// The selection when there is no genie.
    public static final UUID NONE = new UUID(0, 0);

    public static GeniesState of(Tuple<Genie> genies, Settings settings, Optional<String> environmentKey) {
        return new GeniesState(genies, genies.isEmpty() ? NONE : genies.first().id(), settings,
                               Page.CHAT, environmentKey, true, false, ModelLookUp.NOT_YET, DesktopZoom.FIT,
                               new Area(1030, 760), Instant.now());
    }

    public GeniesState withGenies(Tuple<Genie> genies) { return new GeniesState(genies, selected, settings, page, environmentKey, sidebarShown, narrow, lookUp, zoom, area, now); }
    public GeniesState withSelected(UUID selected)     { return new GeniesState(genies, selected, settings, page, environmentKey, sidebarShown, narrow, lookUp, zoom, area, now); }
    public GeniesState withSettings(Settings settings) { return new GeniesState(genies, selected, settings, page, environmentKey, sidebarShown, narrow, lookUp, zoom, area, now); }
    public GeniesState withPage(Page page)             { return new GeniesState(genies, selected, settings, page, environmentKey, sidebarShown, narrow, lookUp, zoom, area, now); }
    public GeniesState withSidebarShown(boolean shown) { return new GeniesState(genies, selected, settings, page, environmentKey, shown, narrow, lookUp, zoom, area, now); }
    public GeniesState withZoom(DesktopZoom zoom)      { return new GeniesState(genies, selected, settings, page, environmentKey, sidebarShown, narrow, lookUp, zoom, area, now); }
    public GeniesState withLookUp(ModelLookUp lookUp)  { return new GeniesState(genies, selected, settings, page, environmentKey, sidebarShown, narrow, lookUp, zoom, area, now); }
    public GeniesState withNow(Instant now)            { return new GeniesState(genies, selected, settings, page, environmentKey, sidebarShown, narrow, lookUp, zoom, area, now); }

    /// Below this width, in the window's own units, the list of genies and a conversation do not
    /// both fit.
    static final int NARROW = 820;

    /// Folds the window's width into the state, the only place a pixel enters it. Crossing into
    /// narrow hides the list of genies, crossing back shows it; in between, the user shows and
    /// hides it as they like. Leaving a width takes a margin of a tenth, so that dragging the
    /// window's edge across the line does not flicker.
    public GeniesState withViewWidth(int width) {
        boolean nowNarrow = narrow ? width < NARROW * 1.1 : width < NARROW / 1.1;
        if (nowNarrow == narrow) return this;
        return new GeniesState(genies, selected, settings, page, environmentKey, !nowNarrow, nowNarrow, lookUp, zoom, area, now);
    }

    /// From this width of the conversation's area, a genie's desktop is shown beside the chat;
    /// below it, under the chat. The window's grid says the same, in its own terms: its large
    /// size class starts at three fifths of its reference width.
    public static final int SIDE_BY_SIDE_FROM = 660;

    /// Folds the size of the conversation's area into the state, the only place a pixel of it
    /// enters. Rounded to tens, so that dragging a window edge is a handful of changes, not one
    /// per pixel.
    public GeniesState withArea(int width, int height) {
        Area rounded = new Area(width / 10 * 10, height / 10 * 10);
        return rounded.equals(area) ? this : new GeniesState(genies, selected, settings, page, environmentKey,
                                                            sidebarShown, narrow, lookUp, zoom, rounded, now);
    }

    /// Below this width of the conversation's area, the header's buttons do not fit with their
    /// words, and show only their signs.
    static final int HEADER_WITH_WORDS_FROM = 780;

    /// Whether the header shows its buttons with words, which needs more room than a narrow
    /// window: the list of genies may take part of a window that is not narrow yet.
    public boolean roomForWords() { return !narrow && area.width() >= HEADER_WITH_WORDS_FROM; }

    /// Whether the chat and the desktop fit side by side.
    public boolean sideBySide() { return area.width() >= SIDE_BY_SIDE_FROM; }

    /// How tall the chat is. It has the whole height, unless the desktop is shown below it; then
    /// the two share the height, each keeping enough to be usable, and the page scrolls.
    public int chatHeight() {
        boolean shared = genie().desktopShown() && genie().phase().isAwake() && !sideBySide();
        return shared ? Math.max(380, area.height() * 3 / 5) : Math.max(240, area.height());
    }

    /// How tall the desktop is: the whole height beside the chat, half of it below.
    public int desktopHeight() {
        return sideBySide() ? Math.max(240, area.height()) : Math.max(260, area.height() / 2);
    }

    /// The genie the chat shows, or an empty stand-in when there is none, so the window always
    /// has something to bind to.
    public Genie genie() {
        return find(selected).orElse(STAND_IN);
    }

    /// Writes the selected genie back. For the window's lenses; changes to a genie by id go
    /// through [#update].
    public GeniesState withGenie(Genie changed) {
        return find(changed.id()).isPresent() ? update(changed.id(), ignored -> changed) : this;
    }

    public boolean hasGenies() { return !genies.isEmpty(); }

    public Optional<Genie> find(UUID id) {
        for (Genie genie : genies) if (genie.id().equals(id)) return Optional.of(genie);
        return Optional.empty();
    }

    /// Changes one genie, if it still exists. Events from a genie's lamp arrive by id, and may
    /// arrive after the user deleted it.
    public GeniesState update(UUID id, UnaryOperator<Genie> change) {
        return withGenies(genies.map(genie -> genie.id().equals(id) ? change.apply(genie) : genie));
    }

    /// Adds a genie and shows its chat.
    public GeniesState add(Genie genie) {
        return withGenies(genies.add(genie)).withSelected(genie.id()).withPage(Page.CHAT);
    }

    /// Removes a genie. The chat then shows the one before it, if any.
    public GeniesState remove(UUID id) {
        int index = indexOf(id);
        if (index < 0) return this;
        Tuple<Genie> rest = genies.removeAt(index);
        UUID next = !selected.equals(id) ? selected
                  : rest.isEmpty() ? NONE : rest.get(Math.max(0, index - 1)).id();
        return withGenies(rest).withSelected(next);
    }

    /// Shows genie `id`: its chat, or its schedule when a schedule is on show.
    public GeniesState select(UUID id) {
        return find(id).isPresent() ? withSelected(id).withPage(page == Page.SCHEDULE ? Page.SCHEDULE : Page.CHAT) : this;
    }

    /// The selected genie's schedule, laid out around [#now].
    public Timeline timeline() {
        return Timeline.of(genie().schedule(), now);
    }

    /// [#now] on the clock of the selected genie's schedule.
    public LocalDateTime localNow() {
        return LocalDateTime.ofInstant(now, genie().schedule().zone());
    }

    /// The settings are asking the service they name for its models.
    public GeniesState askingForModels() {
        Tuple<String> known = lookUp.isFor(settings) ? lookUp.models() : Tuple.of(String.class);
        return withLookUp(new ModelLookUp(settings.place(), settings.service(), known, "Asking for the models…"));
    }

    /// The models the service at `service`, for `place`, offered. If that is still the service
    /// the settings name, and no model was chosen there yet, the first one is.
    public GeniesState modelsFound(Settings.Place place, String service, Tuple<String> found) {
        Tuple<String> models = found.sort(String.CASE_INSENSITIVE_ORDER);
        String note = models.isEmpty()
                        ? (place == Settings.Place.EDEN_AI ? "Eden AI offers no models in the EU just now."
                           : "The model server offers no models yet; pull one first, such as `ollama pull qwen2.5:7b`.")
                    : place == Settings.Place.EDEN_AI ? "Eden AI offers " + models.size() + " models in the EU."
                    : models.size() == 1 ? "The model server offers one model."
                    : "The model server offers " + models.size() + " models.";
        ModelLookUp answer = new ModelLookUp(place, service, models, note);
        Settings chosen = answer.isFor(settings) && settings.model().isBlank() && !models.isEmpty()
                ? settings.withModel(models.first()) : settings;
        return withSettings(chosen).withLookUp(answer);
    }

    /// Asking the service at `service`, for `place`, for its models failed, for the reason given.
    public GeniesState modelsNotFound(Settings.Place place, String service, String why) {
        return withLookUp(new ModelLookUp(place, service, Tuple.of(String.class), why));
    }

    /// Why genies cannot reach their model with the current settings, or nothing.
    public Optional<String> settingsProblem() {
        return settings.problem(environmentKey);
    }

    /// A name for a new genie that no other genie has yet.
    public String freshName() {
        for (int number = genies.size() + 1; ; number++) {
            String name = "Genie " + number;
            if (genies.stream().noneMatch(genie -> genie.name().equals(name))) return name;
        }
    }

    private int indexOf(UUID id) {
        for (int i = 0; i < genies.size(); i++) if (genies.get(i).id().equals(id)) return i;
        return -1;
    }

    private static final Genie STAND_IN = Genie.asleep(NONE, "");
}
