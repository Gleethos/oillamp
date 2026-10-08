package gui

import dev.gui.model.Genie
import dev.gui.model.GeniesState
import dev.gui.model.Settings
import dev.gui.model.Split
import spock.lang.Specification
import sprouts.Tuple

/**
 *  How the chat and a genie's desktop share the room they have, beside each other in a wide
 *  window, one under the other in a narrow one.
 *
 *  <p>A grip between the two lets the user give one more room and the other less. Until they
 *  drag it, Genies picks the share. All of it is the window's state, so it is pinned here
 *  without a window, by feeding the state the area's size and the drags.
 */
class SharingTheRoomWithTheDesktopSpec extends Specification {

    def 'Until the user drags, Genies picks how much room the chat gets'() {
        reportInfo """
            Beside the desktop, the chat gets five twelfths of the width, but no more than 560
            units, which reads comfortably: on a wide screen, the rest goes to the desktop. Under
            the chat, in an area narrower than 660 units, the desktop gets two fifths of the
            height. A grip 9 units thick is between them.
        """
        given:
            def watching = Genie.named('Aladdin').withPhase(Genie.Phase.READY).withDesktopShown(true)
            def state = GeniesState.of(Tuple.of(Genie, watching), Settings.defaults(), Optional.empty())
        when:
            def shared = state.withArea(width, height)
        then:
            shared.sideBySide() == beside
            shared.chatPart() == chat
            shared.desktopPart() == desktop
        where:
            width | height | beside | chat | desktop
            960   | 700    | true   | 400  | 551
            1550  | 800    | true   | 560  | 981
            620   | 800    | false  | 480  | 311
    }

    def 'The user drags the grip to share the room differently, and the share stays'() {
        reportInfo """
            Dragged, the chat keeps its share of the area as the window grows or shrinks. Each
            arrangement has its own share: what the user gave the chat beside the desktop does
            not change how tall it is above it. Neither the chat nor the desktop gets less than it
            needs, 280 units for the chat and 240 for the desktop. A double click on the grip lets
            Genies pick again.
        """
        given:
            def watching = Genie.named('Aladdin').withPhase(Genie.Phase.READY).withDesktopShown(true)
            def state = GeniesState.of(Tuple.of(Genie, watching), Settings.defaults(), Optional.empty())
                                   .withArea(1000, 700)
        when:
            def dragged = state.withChatPart(300).splitReleased(state.split())
        then:
            dragged.chatPart() == 300
            dragged.withArea(2000, 700).chatPart() == 600
            dragged.withArea(620, 800).chatPart() == 480
        and:
            state.withChatPart(100).splitReleased(state.split()).chatPart() == Split.LEAST_CHAT
            state.withChatPart(800).splitReleased(state.split()).desktopPart() == Split.LEAST_DESKTOP
            dragged.withSplit(Split.PICKED).chatPart() == 417
    }

    def 'Dragged nearly all the way toward the desktop, letting go closes it'() {
        reportInfo """
            While the user holds the grip, the desktop may get less room than it needs, down to
            nothing, which shows what letting go does. Let go with less than half of the 240 units
            it needs, the desktop closes. Opened again, it is as large as before that drag.
        """
        given:
            def watching = Genie.named('Aladdin').withPhase(Genie.Phase.READY).withDesktopShown(true)
            def state = GeniesState.of(Tuple.of(Genie, watching), Settings.defaults(), Optional.empty())
                                   .withArea(1000, 700)
        when:
            def held = state.withChatPart(900)
            def closed = held.splitReleased(state.split())
        then:
            held.desktopPart() == 91
            held.desktopOnScreen()
            !closed.desktopOnScreen()
        when:
            def reopened = closed.withGenie(closed.genie().withDesktopShown(true))
        then:
            reopened.chatPart() == state.chatPart()
    }
}
