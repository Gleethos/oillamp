package dev.gui.view

import dev.gui.model.Entry
import dev.gui.model.Genie
import dev.gui.model.Transcript
import spock.lang.Specification
import sprouts.Tuple

import java.awt.Color
import java.awt.image.BufferedImage

import static dev.gui.view.GenieSvgUtil.Accessory.*
import static dev.gui.view.GenieSvgUtil.Pose.*

/**
 *  How Pip, the genie mascot, is drawn: what a genie looks like and which pose it is in.
 *
 *  <p>{@link GenieSvgUtil} writes each genie as SVG text from rows of letters. Both its look and its
 *  pose follow from values, so they are pinned here without a window.
 */
class DrawingAGenieSpec extends Specification {

    def 'A genie looks the same every time, and the genies differ from one another'() {
        reportInfo """
            Nothing about a genie's looks is kept: its body colour and what it wears follow from
            its id. So the same genie is drawn the same way each time Genies starts, while among
            many genies every colour turns up, and none wears two hats.
        """
        given:
            def id = UUID.randomUUID()
            def many = (1..400).collect { GenieSvgUtil.appearanceOf(UUID.randomUUID()) }
            def hats = [TURBAN, FEZ, TOPKNOT, FLAME] as Set
        expect:
            GenieSvgUtil.appearanceOf(id) == GenieSvgUtil.appearanceOf(id)
            many*.colour() as Set == GenieSvgUtil.COLOURS as Set
            many.every { (it.accessories().intersect(hats)).size() <= 1 }
            many.any { it.accessories().isEmpty() }
            many.any { it.accessories().containsAll([VEST, CUFFS, EARRING]) }
    }

    def 'The pose shows what the genie does'() {
        reportInfo """
            Asleep, or still waking, the genie is in its lamp and sleeps. Awake it waits; broken it
            is dizzy. While it works, it thinks, unless the newest thing in its conversation is one
            of its tools still running: then it works.
        """
        given:
            def genie = Genie.named('Juniper')
            def tool = Entry.of(Entry.Kind.TOOL, 'ls').withState(Entry.State.WRITING)
            def thought = Entry.of(Entry.Kind.THINKING, 'hmm').withState(Entry.State.WRITING)
            def withLast = { Entry last -> new Transcript(Tuple.of(Entry, Entry.of(Entry.Kind.YOU, 'hi'), last)) }
        expect:
            GenieSvgUtil.poseOf(genie) == ASLEEP
            GenieSvgUtil.poseOf(genie.withPhase(Genie.Phase.WAKING)) == ASLEEP
            GenieSvgUtil.poseOf(genie.withPhase(Genie.Phase.READY)) == AWAKE
            GenieSvgUtil.poseOf(genie.withPhase(Genie.Phase.BROKEN)) == DIZZY
            GenieSvgUtil.poseOf(genie.withPhase(Genie.Phase.WORKING)) == THINKING
            GenieSvgUtil.poseOf(genie.withPhase(Genie.Phase.WORKING).withTranscript(withLast(thought))) == THINKING
            GenieSvgUtil.poseOf(genie.withPhase(Genie.Phase.WORKING).withTranscript(withLast(tool))) == WORKING
            GenieSvgUtil.poseOf(genie.withPhase(Genie.Phase.WORKING).withTranscript(withLast(tool.withState(Entry.State.DONE)))) == THINKING
    }

    def 'Beside the rows of its chat, the genie shows what it did then'() {
        reportInfo """
            Each answer, thought and problem in the chat has the genie beside it, in the pose of
            that moment rather than of now: working beside what it said before using tools, awake
            beside an answer, thinking beside its thoughts, and dizzy beside a problem. Only the
            row being written now moves; the others stand still.
        """
        given:
            def answer = Entry.of(Entry.Kind.GENIE, 'Done.')
        expect:
            GenieSvgUtil.poseBeside(answer) == AWAKE
            GenieSvgUtil.poseBeside(answer.withBeforeTools(true)) == WORKING
            GenieSvgUtil.poseBeside(Entry.of(Entry.Kind.THINKING, 'hmm')) == THINKING
            GenieSvgUtil.poseBeside(Entry.of(Entry.Kind.NOTICE, 'no model').withState(Entry.State.FAILED)) == DIZZY
    }

    def 'An animation shows each of its frames in turn'() {
        reportInfo """
            Thinking and working are animations, driven by a pulse that loops from 0 to 1. Each
            frame gets an equal share of the loop, and the end of the loop is the last frame.
        """
        expect:
            [0.0d, 0.24d, 0.25d, 0.5d, 0.99d, 1.0d].collect { GenieSvgUtil.frameAt(THINKING, it) } == [0, 0, 1, 2, 3, 3]
            [0.0d, 0.49d, 0.5d, 1.0d].collect { GenieSvgUtil.frameAt(WORKING, it) } == [0, 0, 1, 1]
            GenieSvgUtil.frameAt(AWAKE, 0.7d) == 0
    }

    def 'Every colour, outfit, pose and frame can be drawn'() {
        reportInfo """
            Accessories and the parts of a pose are laid over the body at fixed places. Each of
            them has to stay inside the picture and use only letters that have a colour, in every
            combination, or a genie could not be drawn. The picture is always twenty pixels square.
        """
        given:
            def outfits = [[] as Set] + [TURBAN, FEZ, TOPKNOT, FLAME].collect { hat -> [hat, VEST, CUFFS, EARRING] as Set }
        when:
            def drawn = []
            for (colour in GenieSvgUtil.COLOURS)
                for (outfit in outfits)
                    for (pose in GenieSvgUtil.Pose.values())
                        for (frame in 0..<pose.frames)
                            drawn << GenieSvgUtil.svg(new GenieSvgUtil.Appearance(colour, outfit), pose, frame)
        then:
            drawn.size() == 8 * 5 * 11
            drawn.every { it.startsWith("<svg xmlns='http://www.w3.org/2000/svg' width='20' height='20'") && it.contains('<path') }
    }

    def 'The welcome shows the lamp lighting, and a genie taking form from its flame'() {
        reportInfo """
            The welcome's picture is painted from its play and the moment alone. Before its flame
            catches, there is only the lamp; while the flame burns, it is the lamp's amber, and no
            genie is there. As the flame turns to pixels, it takes on the genie's colour, and once
            the genie has taken form it stays, however long the welcome is shown. Clicked away, it
            flies apart, the lamp stands cold until the next genie's flame catches, and the next
            genie takes form in its own colour.
        """
        given:
            def genie = GenieSvgUtil.appearanceOf(UUID.fromString('0c4b0a34-1f0e-4f4e-9d0f-6b3f5a0e2d11'))
            def play = new WelcomeScene.Play(genie, 0, genie, -1)
            def paintedAt = { WelcomeScene.Play shown, double clock ->
                def picture = new BufferedImage(700, 300, BufferedImage.TYPE_INT_RGB)
                WelcomeScene.paint(picture.createGraphics(), 700, 300, shown, clock)
                def colours = [] as Set
                for (int x = 0; x < 700; x += 2) for (int y = 0; y < 300; y += 2) colours << picture.getRGB(x, y)
                colours
            }
            def colour = genie.colour().getRGB()
            def amber = new Color(0xf0, 0xa9, 0x40).getRGB()
            def again = play.poof(20)
        expect:
            !(amber in paintedAt(play, 0.5)) && !(colour in paintedAt(play, 0.5))
            amber in paintedAt(play, 3.0)
            !(colour in paintedAt(play, 3.0))
            colour in paintedAt(play, 6.5)
            [8.5d, 12.0d, 60.0d, 3600.0d].every { colour in paintedAt(play, it) }
            !(colour in paintedAt(again, 21.15)) && !(amber in paintedAt(again, 21.15))
            again.pip().colour().getRGB() in paintedAt(again, 21.1 + 8.5)
            !play.formed(7.0) && play.formed(8.0)
    }
}
