package oillamp

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.domain.JavaModifier
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import spock.lang.Shared
import spock.lang.Specification

/**
 *  Two structural promises that are easy to break by accident and expensive to notice late.
 *  They are checked here rather than left to review, because both are about what is *absent*,
 *  and absences are exactly what review misses.
 */
class TheShapeOfTheCodeSpec extends Specification {

    @Shared JavaClasses code = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages('dev.oillamp', 'dev.lamp')

    /** The engine, as far as anything outside it is concerned. */
    static final Set<String> ENGINE_API = ['OilLamp', 'Machine'] as Set

    /** What an application that embeds oillamp sees: the words oillamp speaks in, and a lamp. */
    static final Set<String> EMBEDDING_API = ['LampEvent', 'Problem', 'ExitStatus', 'Lamp'] as Set

    static final Set<String> PUBLIC_API = ENGINE_API + EMBEDDING_API

    def 'The engine exposes two types, and nothing else'() {
        reportInfo """
            The public surface is the part that cannot be changed later without breaking someone.
            Keeping the engine's to two types - the entry point and the machine it runs against -
            is what leaves everything else free to be rewritten. The events, problems and exit
            codes it speaks in are public too, but they live in `dev.lamp`, because an
            application that embeds oillamp reads them as well.

            This is enforced by the compiler rather than by convention: the engine is one package,
            so a class that is not marked public simply cannot be reached from outside it. This
            scenario exists to catch the moment someone adds `public` to another class - which is
            easy to do while debugging and easy to forget to undo.

            The scenarios in this package are themselves the proof that these are enough: they
            live outside `dev.oillamp` and drive the whole tool through nothing else.
        """
        when: 'we look at every top-level type oillamp defines'
            var exposed = code.findAll { it.packageName == 'dev.oillamp' }
                              .findAll { it.enclosingClass.empty && !it.simpleName.contains('\$') }
                              .findAll { it.modifiers.contains(JavaModifier.PUBLIC) }
                              .collect { it.simpleName }
                              .toSet()

        then: 'exactly the two are public'
            exposed.sort() == ENGINE_API.sort()
    }

    def 'The embedding package exposes only what an application needs'() {
        reportInfo """
            `dev.lamp` is what an application such as a game compiles against. Whatever is public
            there is a promise to that application, so the list is checked like the engine's.
        """
        when:
            var exposed = code.findAll { it.packageName == 'dev.lamp' }
                              .findAll { it.enclosingClass.empty && !it.simpleName.contains('\$') }
                              .findAll { it.modifiers.contains(JavaModifier.PUBLIC) }
                              .collect { it.simpleName }
                              .toSet()

        then:
            exposed.sort() == EMBEDDING_API.sort()
    }

    def 'The embedding package does not depend on the engine'() {
        reportInfo """
            The engine runs in a process of its own, and an application talks to it. If
            `dev.lamp` imported `dev.oillamp`, that separation would be a convention rather than
            a fact, and the application would be one careless import away from running the
            supervisor inside itself. The engine may use `dev.lamp`; never the other way round.
        """
        when:
            var reaching = [] as Set
            code.findAll { it.packageName == 'dev.lamp' }.each { type ->
                type.directDependenciesFromSelf.each { dependency ->
                    if (dependency.targetClass.packageName == 'dev.oillamp')
                        reaching << "${type.simpleName} -> ${dependency.targetClass.simpleName}"
                }
            }

        then:
            reaching.isEmpty()
    }

    def 'Nothing internal leaks out through the types that are public'() {
        reportInfo """
            A small public API is not small if one of its methods returns an internal type: the
            caller can then reach everything that type touches, and the freedom to change it is
            gone without anyone deciding to give it up.

            So every parameter and return type of every public method must itself be public -
            either one of the public types, one of their nested types, or something from the JDK
            or a library.
        """
        when:
            var leaks = []
            code.findAll { it.simpleName in PUBLIC_API }.each { type ->
                type.methods.findAll { it.modifiers.contains(JavaModifier.PUBLIC) }.each { method ->
                    (method.rawParameterTypes + [method.rawReturnType]).each { used ->
                        if (used.packageName in ['dev.oillamp', 'dev.lamp'] && !used.modifiers.contains(JavaModifier.PUBLIC))
                            leaks << "${type.simpleName}.${method.name} exposes ${used.simpleName}"
                    }
                }
            }

        then:
            leaks.isEmpty()
    }

    def 'The code that decides things does not also perform them'() {
        reportInfo """
            Everything oillamp decides - which packages to install, whether a directory may become
            a lamp, which recordings to delete, what the plan is - is a pure function over values
            that were gathered elsewhere. Only a named handful of classes actually touch the
            filesystem, spawn processes, read the clock or generate randomness.

            That split is why the scenarios can cover broken machines, dangerous directories and
            malformed configuration without installing anything or needing a container. If a planner starts reading a file directly, that stops being true - and
            it stops quietly, one class at a time. Hence this test.
        """
        given: 'the classes whose job is to touch the outside world'
            var allowed = ['RealMachine', 'SimulatedMachine', 'Filesystem', 'LampLock', 'HostProbeUtil',
                           'StepRunner', 'LampPhase', 'HostPhase', 'Commands', 'ConsoleRenderer',
                           'OilLamp', 'Invocation', 'Machine',
                           // The session. These three bind sockets, move bytes between them
                           // and start the windows. They decide nothing: every decision a
                           // session makes is SessionMachine's.
                           'Supervisor', 'Relay', 'Control',
                           // The egress proxy: sockets, DNS and byte copying. Whether a
                           // connection is allowed is decided by Policy, a pure function.
                           'Egress',
                           // The lamp's history: reads the agent's home and writes git
                           // objects, as Filesystem does the lamp's other files. What a
                           // snapshot looks like is decided by GitFormat, which is pure.
                           'History',
                           // The agent the session holds, and the runs that wake it: a process
                           // in the sandbox, and threads that wait for it. What a run's
                           // prompt says is decided by WakePrompt, and the rules of the
                           // schedule by Schedule, which are both pure.
                           'Harness', 'Runs',
                           // An application's handle on a lamp: starts the engine's process
                           // and reads its output. It decides nothing about the sandbox.
                           'Lamp'] as Set

        and: 'the things only they may use'
            var effects = ['java.nio.file.Files', 'java.lang.ProcessBuilder', 'java.lang.Process',
                           'java.security.SecureRandom', 'java.lang.Thread', 'java.lang.System']

        when: 'a nested class is judged by the class it lives in, not by its own name'
            var offenders = [] as Set
            code.findAll { !(outermostNameOf(it) in allowed) }.each { type ->
                type.directDependenciesFromSelf.each { dependency ->
                    if (dependency.targetClass.fullName in effects)
                        offenders << "${type.simpleName} -> ${dependency.targetClass.simpleName}"
                }
            }

        then: 'no decision-making class reaches for an effect'
            offenders.isEmpty()
    }

    /**
     *  The name of the top-level class a type lives in. A helper nested inside an allowed class
     *  is part of that class's job - ArchUnit reports it under its own short name, which would
     *  otherwise make every private inner worker look like a new offender.
     */
    private static String outermostNameOf(type) {
        var outer = type
        while (outer.enclosingClass.present) outer = outer.enclosingClass.get()
        outer.simpleName
    }
}
