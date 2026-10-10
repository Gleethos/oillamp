package dev.gui.view

import spock.lang.Specification
import spock.lang.Timeout
import swingtree.UI
import swingtree.components.JScrollPanels

import javax.swing.JPanel
import javax.swing.SwingUtilities
import java.awt.Container
import java.awt.Dimension
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.util.concurrent.TimeUnit

/**
 *  When {@link FollowTheEnd} scrolls the chat to the bottom, and when it leaves the scroll position alone.
 *
 *  <p>Each scenario builds a {@link JScrollPanels}, the chat's scroll pane, with plain rows of a
 *  fixed height. Swing lays out only components inside a window, so each step lays the pane out
 *  by calling {@code doLayout()} from the pane down. {@link FollowTheEnd} scrolls in a task it
 *  queues on Swing's event thread, so each step then waits for that queue to run.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class FollowingTheEndOfAChatSpec extends Specification {

    def 'The chat stays at the bottom while it grows or shrinks'() {
        reportInfo """
            The user is at the bottom. A row is added, the visible height shrinks by 60 as the
            thinking bar appears under the chat, a row is removed, and another row is added.
            After each change the chat is scrolled to the bottom. Removing a row is the tricky
            case: Swing first sets a smaller scroll position, which looks like the user
            scrolling up, and only then the smaller content height.
        """
        given:
            Closure layOut
            layOut = { Container it ->
                it.doLayout()
                it.components.each { inside -> if (inside instanceof Container) layOut(inside) }
            }
            JScrollPanels pane = null
            SwingUtilities.invokeAndWait {
                pane = UI.scrollPanels().get(JScrollPanels)
                pane.setSize(300, 400)
                10.times { pane.contentPanel.add(new JPanel(preferredSize: new Dimension(200, 100))) }
                layOut(pane)
                FollowTheEnd.on(pane).toEnd()
            }
            SwingUtilities.invokeAndWait {}
            var range = pane.verticalScrollBar.model
            var atEnd = { range.value + range.extent == range.maximum }

        expect:
            atEnd()

        when: 'an answer adds a row'
            SwingUtilities.invokeAndWait {
                pane.contentPanel.add(new JPanel(preferredSize: new Dimension(200, 100)))
                pane.contentPanel.revalidate()
                layOut(pane)
            }
            SwingUtilities.invokeAndWait {}

        then:
            atEnd()

        when: 'the bar under the chat takes 60 of the view'
            SwingUtilities.invokeAndWait {
                pane.setSize(300, 340)
                layOut(pane)
            }
            SwingUtilities.invokeAndWait {}

        then:
            atEnd()

        when: 'an answer that never got a word is taken away, and the next one comes in'
            SwingUtilities.invokeAndWait {
                pane.contentPanel.remove(pane.contentPanel.componentCount - 1)
                pane.contentPanel.revalidate()
                layOut(pane)
            }
            SwingUtilities.invokeAndWait {}
            SwingUtilities.invokeAndWait {
                pane.contentPanel.add(new JPanel(preferredSize: new Dimension(200, 100)))
                pane.contentPanel.revalidate()
                layOut(pane)
            }
            SwingUtilities.invokeAndWait {}

        then:
            atEnd()
    }

    def 'Scrolling up stops the scrolling to the bottom, and scrolling back down starts it again'() {
        reportInfo """
            The user turns the mouse wheel up one notch. A row is added, and the scroll position
            does not change. The user then turns the wheel down until the chat is at the bottom,
            a row is added, and the chat is scrolled to the bottom again.
        """
        given:
            Closure layOut
            layOut = { Container it ->
                it.doLayout()
                it.components.each { inside -> if (inside instanceof Container) layOut(inside) }
            }
            JScrollPanels pane = null
            SwingUtilities.invokeAndWait {
                pane = UI.scrollPanels().get(JScrollPanels)
                pane.setSize(300, 400)
                10.times { pane.contentPanel.add(new JPanel(preferredSize: new Dimension(200, 100))) }
                layOut(pane)
                FollowTheEnd.on(pane).toEnd()
            }
            SwingUtilities.invokeAndWait {}
            var range = pane.verticalScrollBar.model
            var wheel = { int notches ->
                SwingUtilities.invokeAndWait {
                    pane.dispatchEvent(new MouseWheelEvent(pane, MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(), 0,
                            10, 10, 10, 10, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, notches, notches as double))
                }
                SwingUtilities.invokeAndWait {}
            }
            var grow = {
                SwingUtilities.invokeAndWait {
                    pane.contentPanel.add(new JPanel(preferredSize: new Dimension(200, 100)))
                    pane.contentPanel.revalidate()
                    layOut(pane)
                }
                SwingUtilities.invokeAndWait {}
            }

        when: 'the user turns the wheel up one notch'
            wheel(-1)
            int readAt = range.value
            grow()

        then:
            readAt < range.maximum - range.extent
            range.value == readAt

        when: 'the user turns the wheel down to the bottom'
            int notches = 0
            while (range.value + range.extent < range.maximum && notches++ < 100) wheel(1)
            grow()

        then:
            range.value + range.extent == range.maximum
    }

    def 'After the user scrolled up, no change to the chat moves the scroll position'() {
        reportInfo """
            The user turns the wheel up until the scroll position is at most 250. Then, as during
            a reply, the thinking bar appears and disappears under the chat, a tool row and a
            reply row are added, the reply row grows three times, and the window gets taller.
            Last, as when switching conversation versions, the six rows at the end are replaced
            by rows that are flat at first and get their height later, so the chat is too short
            for the scroll position for a moment. After all of it, the scroll position is where
            the user left it.
        """
        given:
            Closure layOut
            layOut = { Container it ->
                it.doLayout()
                it.components.each { inside -> if (inside instanceof Container) layOut(inside) }
            }
            JScrollPanels pane = null
            SwingUtilities.invokeAndWait {
                pane = UI.scrollPanels().get(JScrollPanels)
                pane.setSize(300, 400)
                10.times { pane.contentPanel.add(new JPanel(preferredSize: new Dimension(200, 100))) }
                layOut(pane)
                FollowTheEnd.on(pane).toEnd()
            }
            SwingUtilities.invokeAndWait {}
            var range = pane.verticalScrollBar.model
            var wheel = { int notches ->
                SwingUtilities.invokeAndWait {
                    pane.dispatchEvent(new MouseWheelEvent(pane, MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(), 0,
                            10, 10, 10, 10, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, notches, notches as double))
                }
                SwingUtilities.invokeAndWait {}
            }
            var change = { Closure what ->
                SwingUtilities.invokeAndWait {
                    what()
                    pane.contentPanel.revalidate()
                    layOut(pane)
                }
                SwingUtilities.invokeAndWait {}
            }

        when: 'the user scrolls up to read'
            int notches = 0
            while (range.value > 250 && notches++ < 100) wheel(-1)
            int readAt = range.value

        and: 'the genie thinks, uses a tool, answers, and the answer grows'
            change { pane.setSize(300, 340) }
            change { pane.contentPanel.add(new JPanel(preferredSize: new Dimension(200, 40))) }
            change { pane.setSize(300, 400) }
            change { pane.contentPanel.add(new JPanel(preferredSize: new Dimension(200, 100))) }
            3.times { int piece ->
                change {
                    var answer = pane.contentPanel.getComponent(pane.contentPanel.componentCount - 1)
                    answer.preferredSize = new Dimension(200, 100 + 80 * (piece + 1))
                }
            }

        and: 'the user makes the window taller'
            change { pane.setSize(300, 600) }

        and: 'the last six rows are replaced by flat rows, which then get their height'
            var flat = (1..6).collect { new JPanel(preferredSize: new Dimension(200, 5)) }
            change {
                6.times { pane.contentPanel.remove(pane.contentPanel.componentCount - 1) }
                flat.each { pane.contentPanel.add(it) }
            }
            change { flat.each { it.preferredSize = new Dimension(200, 100) } }

        then:
            readAt <= 250
            range.value == readAt
    }
}
