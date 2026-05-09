package io.github.jwyoon1220.app

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.ConsoleAppender
import org.slf4j.LoggerFactory

object LoggingConfig {

    private const val PATTERN         = "%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n"
    private const val PATTERN_COLORED = "%d{HH:mm:ss.SSS} %highlight(%-5level) %cyan([%thread]) %magenta(%logger{36}) - %msg%n"

    fun apply(debug: Boolean, console: Boolean) {
        val ctx  = LoggerFactory.getILoggerFactory() as LoggerContext
        val root = ctx.getLogger(Logger.ROOT_LOGGER_NAME)

        if (debug) {
            root.level = Level.DEBUG
        }

        if (console) {
            val encoder = PatternLayoutEncoder().apply {
                context = ctx
                pattern = PATTERN_COLORED
                start()
            }
            val appender = ConsoleAppender<ILoggingEvent>().apply {
                context = ctx
                name    = "CONSOLE"
                this.encoder = encoder
                start()
            }
            root.addAppender(appender)
        }
    }
}
