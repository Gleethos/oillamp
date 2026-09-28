package gui

import dev.gui.model.DesktopZoom
import spock.lang.Specification

/**
 *  How large a genie's desktop is shown next to the chat.
 *
 *  <p>A genie's desktop has a size of its own, usually larger than the room beside the chat. By
 *  default it is fitted into that room, so all of it is visible. To read small text, the user
 *  zooms in, with the buttons above it or Control and the mouse wheel; it then scrolls.
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
            Between the buttons, the current size is shown: fitted, or a percentage.
        """
        expect:
            DesktopZoom.FIT.label() == 'Fit'
            new DesktopZoom(1.25).label() == '125%'
    }
}
