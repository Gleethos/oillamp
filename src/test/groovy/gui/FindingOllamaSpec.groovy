package gui

import dev.gui.genie.OllamaKeeper
import spock.lang.Specification

/**
 *  How Genies reads what the computer has for running a model, from what Linux and NVIDIA's
 *  tool print.
 */
class FindingOllamaSpec extends Specification {

    def 'The memory is read from /proc/meminfo'() {
        reportInfo """
            Linux lists the computer's memory in /proc/meminfo, in kibibytes. Genies takes the
            whole of it, not what is free now: a model is loaded when it is needed, and the rest
            of the computer makes room.
        """
        given:
            def meminfo = '''\
                MemTotal:       64446204 kB
                MemFree:        31767292 kB
                MemAvailable:   48424376 kB
                '''.stripIndent()
        expect:
            OllamaKeeper.memoryIn(meminfo) == 64446204L * 1024
            OllamaKeeper.memoryIn('') == 0
    }

    def 'NVIDIA cards are read from what nvidia-smi prints, the small ones left out'() {
        reportInfo """
            nvidia-smi lists each card with its memory in mebibytes. A card with less than four
            gigabytes of its own runs no model faster than the processor, so it counts as none,
            and so does anything that is not a card, such as a complaint about a missing driver.
        """
        given:
            def printed = '''\
                NVIDIA GeForce RTX 4070, 12282
                NVIDIA GeForce MX150, 2048
                NVIDIA-SMI has failed because it couldn't communicate with the NVIDIA driver.
                '''.stripIndent()
        when:
            def cards = OllamaKeeper.nvidiaIn(printed)
        then:
            cards*.name() == ['NVIDIA GeForce RTX 4070']
            cards*.memory() == [12282L << 20]
    }
}
