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
            .importPackages('dev.oillamp')

    /** The whole of oillamp, as far as anything outside it is concerned. */
    static final Set<String> PUBLIC_API = ['OilLamp', 'Machine', 'LampEvent', 'Problem', 'ExitStatus'] as Set

    def 'oillamp exposes five types, and nothing else'() {
        reportInfo """
            The public surface is the part that cannot be changed later without breaking someone.
            Keeping it to five types - the entry point, the machine it runs against, the events it
            emits and the problems it reports, plus an exit code - is what leaves everything else
            free to be rewritten.

            This is enforced by the compiler rather than by convention: oillamp is one package, so
            a class that is not marked public simply cannot be reached from outside it. This
            scenario exists to catch the moment someone adds `public` to a sixth class - which is
            easy to do while debugging and easy to forget to undo.

            The scenarios in this package are themselves the proof that the five are enough: they
            live outside `dev.oillamp` and drive the whole tool through nothing else.
        """
        when: 'we look at every top-level type oillamp defines'
            var exposed = code.findAll { it.packageName == 'dev.oillamp' }
                              .findAll { it.enclosingClass.empty && !it.simpleName.contains('\$') }
                              .findAll { it.modifiers.contains(JavaModifier.PUBLIC) }
                              .collect { it.simpleName }
                              .toSet()

        then: 'exactly the five are public'
            exposed.sort() == PUBLIC_API.sort()
    }

    def 'Nothing internal leaks out through the types that are public'() {
        reportInfo """
            A small public API is not small if one of its methods returns an internal type: the
            caller can then reach everything that type touches, and the freedom to change it is
            gone without anyone deciding to give it up.

            So every parameter and return type of every public method must itself be public -
            either one of the five, one of their nested types, or something from the JDK or a
            library.
        """
        when:
            var leaks = []
            code.findAll { it.simpleName in PUBLIC_API }.each { type ->
                type.methods.findAll { it.modifiers.contains(JavaModifier.PUBLIC) }.each { method ->
                    (method.rawParameterTypes + [method.rawReturnType]).each { used ->
                        if (used.packageName == 'dev.oillamp' && !used.modifiers.contains(JavaModifier.PUBLIC))
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

            That split is why this project has 29 scenarios covering broken machines, hostile
            directories and malformed configuration without installing anything or needing a
            container. If a planner starts reading a file directly, that stops being true - and
            it stops quietly, one class at a time. Hence this test.
        """
        given: 'the classes whose job is to touch the outside world'
            var allowed = ['RealMachine', 'SimulatedMachine', 'Filesystem', 'LampLock', 'HostProbe',
                           'StepRunner', 'LampPhase', 'HostPhase', 'Commands', 'ConsoleRenderer',
                           'OilLamp', 'Invocation', 'Machine'] as Set

        and: 'the things only they may use'
            var effects = ['java.nio.file.Files', 'java.lang.ProcessBuilder', 'java.lang.Process',
                           'java.security.SecureRandom', 'java.lang.Thread', 'java.lang.System']

        when:
            var offenders = [] as Set
            code.findAll { !(it.simpleName.split('\\$')[0] in allowed) }.each { type ->
                type.directDependenciesFromSelf.each { dependency ->
                    if (dependency.targetClass.fullName in effects)
                        offenders << "${type.simpleName} -> ${dependency.targetClass.simpleName}"
                }
            }

        then: 'no decision-making class reaches for an effect'
            offenders.isEmpty()
    }
}
