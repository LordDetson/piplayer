package io.github.lorddetson.piplayer

/**
 * A test whose result depends on how busy the device and its host are: it checks a limit on wall-clock time. Ordinary
 * runs leave these tests out (`-e notAnnotation`), and they run in a quiet pass of their own before a step's release
 * (`-e annotation`), with nothing else building or testing on the machine.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class TimingTest
