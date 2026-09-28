package dev.gui.view;

/// The kinds of component the [Look] style sheet paints, tagged onto components with
/// `.group(..)`. Anything whose look depends on data, such as a genie's phase, is styled next to
/// the component instead.
enum Skin {
    FRAME, SIDEBAR, BRAND, SECTION,
    HEADER, TITLE, SUBTITLE, META,
    CARD, EMPTY_TITLE, EMPTY_TEXT,
    FLAME_BUTTON, QUIET_BUTTON, ICON_BUTTON, DANGER_BUTTON,
    INPUT, COMPOSER, PROBLEM, FINE,
    PAGE_SCROLL
}
