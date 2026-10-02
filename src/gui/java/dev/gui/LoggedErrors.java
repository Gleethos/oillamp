package dev.gui;

import org.jspecify.annotations.Nullable;
import org.slf4j.ILoggerFactory;
import org.slf4j.IMarkerFactory;
import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.BasicMarkerFactory;
import org.slf4j.helpers.LegacyAbstractLogger;
import org.slf4j.helpers.MessageFormatter;
import org.slf4j.helpers.NOPMDCAdapter;
import org.slf4j.spi.MDCAdapter;
import org.slf4j.spi.SLF4JServiceProvider;

/// Takes what SwingTree and Sprouts log through slf4j: their errors to the [ErrorLog], their
/// warnings to the error output, the rest nowhere.
///
/// SwingTree catches an exception thrown in an event handler, such as a button's, and logs it
/// instead of letting it through. Without this, Genies would never hear of it: oillamp binds slf4j
/// to nothing, so that it does not talk to the user of the command line.
///
/// Chosen by its name in the system property `slf4j.provider`, which [Genies#main] sets before
/// anything logs. It is not listed as a service, so oillamp's engine, which runs from the same
/// classpath, keeps logging nowhere.
public final class LoggedErrors implements SLF4JServiceProvider {

    private final ILoggerFactory loggers = Logger::new;
    private final IMarkerFactory markers = new BasicMarkerFactory();
    private final MDCAdapter context = new NOPMDCAdapter();

    @Override public ILoggerFactory getLoggerFactory() { return loggers; }
    @Override public IMarkerFactory getMarkerFactory() { return markers; }
    @Override public MDCAdapter getMDCAdapter() { return context; }
    @Override public String getRequestedApiVersion() { return "2.0.99"; }
    @Override public void initialize() { }

    private static final class Logger extends LegacyAbstractLogger {

        private static final long serialVersionUID = 1L;

        Logger(String name) {
            this.name = name;
        }

        @Override public boolean isTraceEnabled() { return false; }
        @Override public boolean isDebugEnabled() { return false; }
        @Override public boolean isInfoEnabled() { return false; }
        @Override public boolean isWarnEnabled() { return true; }
        @Override public boolean isErrorEnabled() { return true; }

        @Override protected @Nullable String getFullyQualifiedCallerName() { return null; }

        @Override protected void handleNormalizedLoggingCall(Level level, @Nullable Marker marker, String pattern,
                                                             Object @Nullable [] arguments, @Nullable Throwable thrown) {
            String message = MessageFormatter.basicArrayFormat(pattern, arguments);
            if (level == Level.ERROR && Thread.getDefaultUncaughtExceptionHandler() instanceof ErrorLog log)
                log.record(name + ", which logged: " + message,
                           thrown != null ? thrown : new IllegalStateException(message));
            else
                System.err.println("genies: " + level + " from " + name + ": " + message
                                   + (thrown == null ? "" : ": " + thrown));
        }
    }
}
