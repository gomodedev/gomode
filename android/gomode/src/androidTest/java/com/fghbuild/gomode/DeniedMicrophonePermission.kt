// Runs each microphone-prompt scenario from a revoked permission in its own instrumentation process.
package com.fghbuild.gomode

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class DeniedMicrophonePermission
