package gui

import dev.gui.model.DesktopZoom
import dev.gui.model.Genie
import dev.gui.model.GeniesState
import dev.gui.model.Settings
import spock.lang.Specification
import sprouts.Tuple

/**
 *  How large a genie's desktop is shown next to the chat.
 *
 *  <p>By default the desktop takes the size of the room beside the chat, its panel, so that what
 *  the genie shows there fills it, pixel for pixel. The user can show it at its own size instead,
 *  which is usually larger than the panel: fitted into it, so all of it is visible, or zoomed in
 *  to read small text, with the buttons above it or Control and the mouse wheel; it then scrolls.
 */
class ZoomingIntoAGeniesDesktopSpec extends Specification {

    def 'Zooming starts from the size fit shows, so the first step is never a jump'() {
        reportInfo """
            Fitted, a large desktop may be shown at 60% of its size. The first click on + then
            goes to the next step above that, 67%, not to some fixed size far away; the first
            click on − to the next step below, 50%.
        """
        expect:
            DesktopZoom.FIT.in(0.6) == new DesktopZoom(0.67)
            DesktopZoom.FIT.out(0.6) == new DesktopZoom(0.5)
    }

    def 'Each step goes to the next size, and stops at the smallest and the largest'() {
        reportInfo """
            The sizes are the ones people know from other programs: 50% to 300%, with 100%
            meaning one pixel of the desktop per pixel of the screen, where text is sharpest.
        """
        expect:
            new DesktopZoom(1.0).in(0.6) == new DesktopZoom(1.25)
            new DesktopZoom(1.0).out(0.6) == new DesktopZoom(0.75)
            new DesktopZoom(3.0).in(0.6) == new DesktopZoom(3.0)
            new DesktopZoom(0.5).out(0.6) == new DesktopZoom(0.5)
    }

    def 'The zoom says what it is'() {
        reportInfo """
            Between the buttons, the current size is shown: the panel's, fitted, or a percentage.
        """
        expect:
            DesktopZoom.PANEL.label() == 'Panel'
            DesktopZoom.FIT.label() == 'Fit'
            new DesktopZoom(1.25).label() == '125%'
    }

    def 'A desktop takes the size of its panel, unless the user chooses otherwise'() {
        reportInfo """
            A genie shows the user something on its desktop, so the desktop fits the panel it is
            shown in, rather than being shrunk into it. The user who would rather move around a
            larger desktop picks Fit or a scale.
        """
        expect:
            GeniesState.of(Tuple.of(Genie), Settings.defaults(), Optional.empty()).zoom() == DesktopZoom.PANEL
            DesktopZoom.PANEL.isPanel() && !DesktopZoom.PANEL.isFit()
            DesktopZoom.FIT.isFit() && !DesktopZoom.FIT.isPanel()
    }

    def 'Zooming from the panel\'s size starts from the scale the desktop is drawn at'() {
        reportInfo """
            While the desktop is on its way to the panel's size, or when it keeps its own size
            because it is recorded, it is drawn fitted. Zooming then starts from there, as it does
            from Fit, and leaves the panel's size: the desktop gets its own size back.
        """
        expect:
            DesktopZoom.PANEL.in(0.6) == new DesktopZoom(0.67)
            DesktopZoom.PANEL.out(0.6) == new DesktopZoom(0.5)
    }
}
