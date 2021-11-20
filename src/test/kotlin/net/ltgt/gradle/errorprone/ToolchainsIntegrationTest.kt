package net.ltgt.gradle.errorprone

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.TruthJUnit.assume
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.TaskOutcome
import org.gradle.util.GradleVersion
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

class ToolchainsIntegrationTest : AbstractPluginIntegrationTest() {

    companion object {
        private val FORKED = "${System.lineSeparator()}Fork: true${System.lineSeparator()}"
        private val NOT_FORKED = "${System.lineSeparator()}Fork: false${System.lineSeparator()}"
        private val JVM_ARG = "${System.lineSeparator()}JVM Arg: "
        private val JVM_ARG_BOOTCLASSPATH = jvmArg("-Xbootclasspath/p:")
        private val JVM_ARG_BOOTCLASSPATH_ERRORPRONE_JAVAC =
            """\Q$JVM_ARG_BOOTCLASSPATH\E.*\Q${File.separator}com.google.errorprone${File.separator}javac${File.separator}9+181-r4173-1${File.separator}\E.*\Q${File.separator}javac-9+181-r4173-1.jar\E(?:\Q${File.pathSeparator}\E|${Regex.escape(System.lineSeparator())})"""
                .toPattern()
        private val JVM_ARGS_STRONG_ENCAPSULATION = ErrorPronePlugin.JVM_ARGS_STRONG_ENCAPSULATION.joinToString(prefix = JVM_ARG, separator = JVM_ARG)

        private fun jvmArg(argPrefix: String) = "$JVM_ARG$argPrefix"
    }

    @BeforeEach
    fun setup() {
        testProjectDir.resolve("gradle.properties").appendText(
            """
            org.gradle.java.installations.auto-download=false
            """.trimIndent()
        )

        buildFile.appendText(
            """
            plugins {
                `java-library`
                id("${ErrorPronePlugin.PLUGIN_ID}")
            }
            repositories {
                mavenCentral()
            }
            dependencies {
                errorprone("com.google.errorprone:error_prone_core:$errorproneVersion")
            }

            tasks {
                val alwaysFail by registering {
                    doFirst {
                        error("Forced failure")
                    }
                }
                val displayCompileJavaOptions by registering {
                    finalizedBy(alwaysFail)
                    doFirst {
                        println("ErrorProne: ${'$'}{if (compileJava.get().options.errorprone.isEnabled.getOrElse(false)) "enabled" else "disabled"}")
                        println("Fork: ${'$'}{compileJava.get().options.isFork}")
                        compileJava.get().options.forkOptions.jvmArgs?.forEach { arg ->
                            println("JVM Arg: ${'$'}arg")
                        }
                    }
                }
                compileJava {
                    finalizedBy(displayCompileJavaOptions)
                    options.forkOptions.jvmArgs!!.add("-XshowSettings")
                }
            }
            """.trimIndent()
        )
        testProjectDir.writeSuccessSource()
    }

    private fun BuildResult.assumeToolchainAvailable() {
        if (task("alwaysFail") == null &&
            output.contains("No compatible toolchains found for request filter")
        ) {
            assume().withMessage("No compatible toolchains found").fail()
        }
    }

    @Test
    fun `fails when configured toolchain is too old`() {
        // given

        // Fake a JDK 7 toolchain, the task should never actually run anyway.
        // We cannot even use a real toolchain and rely on auto-download=false as that
        // would fail too early (when computing task inputs, so our doFirst won't run)
        buildFile.appendText(
            """

            tasks.compileJava {
                javaCompiler.set(object : JavaCompiler {
                    override fun getExecutablePath(): RegularFile = TODO()
                    override fun getMetadata(): JavaInstallationMetadata = object : JavaInstallationMetadata {
                        override fun getLanguageVersion(): JavaLanguageVersion = JavaLanguageVersion.of(7)
                        override fun getInstallationPath(): Directory = TODO()
                        override fun getVendor(): String = TODO()
                        ${
            if (GradleVersion.version(testGradleVersion).baseVersion >= GradleVersion.version("7.1")) {
                """override fun getJavaRuntimeVersion(): String = TODO()
                   override fun getJvmVersion(): String = TODO()"""
            } else {
                ""
            }}
                    }
                })
            }
            """.trimIndent()
        )

        // First test that it's disabled by default

        // when
        testProjectDir.buildWithArgsAndFail("displayCompileJavaOptions").also { result ->
            // then
            assertThat(result.task(":displayCompileJavaOptions")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
            assertThat(result.output).contains("ErrorProne: disabled")
        }

        // Then test that it fails if we force-enable it

        buildFile.appendText(
            """

            tasks.compileJava { options.errorprone.isEnabled.set(true) }
            """.trimIndent()
        )

        // when
        testProjectDir.buildWithArgsAndFail("compileJava").also { result ->
            // then
            assertThat(result.task(":compileJava")?.outcome).isEqualTo(TaskOutcome.FAILED)
            assertThat(result.output).contains(ErrorPronePlugin.TOO_OLD_TOOLCHAIN_ERROR_MESSAGE)
        }
    }

    @Test
    fun `does not configure forking in non-Java 8 or 16+ VM`() {
        // given
        buildFile.appendText(
            """

            java {
                toolchain {
                    languageVersion.set(JavaLanguageVersion.of(11))
                }
            }
            """.trimIndent()
        )

        // when
        testProjectDir.buildWithArgsAndFail("compileJava").also { result ->
            // then
            result.assumeToolchainAvailable()
            assertThat(result.task(":compileJava")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
            assertThat(result.output).contains(NOT_FORKED)
            assertThat(result.output).doesNotContain(JVM_ARG_BOOTCLASSPATH)
            assertThat(result.output).contains(JVM_ARGS_STRONG_ENCAPSULATION)
            // Check that the configured jvm arg is preserved
            assertThat(result.output).contains(jvmArg("-XshowSettings"))
        }

        // Test a forked task

        // given
        buildFile.appendText(
            """

            tasks.compileJava { options.isFork = true }
            """.trimIndent()
        )

        // when
        testProjectDir.buildWithArgsAndFail("compileJava").also { result ->
            // then
            result.assumeToolchainAvailable()
            assertThat(result.task(":compileJava")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
            assertThat(result.output).contains(FORKED)
            assertThat(result.output).doesNotContain(JVM_ARG_BOOTCLASSPATH)
            assertThat(result.output).contains(JVM_ARGS_STRONG_ENCAPSULATION)
            // Check that the configured jvm arg is preserved
            assertThat(result.output).contains(jvmArg("-XshowSettings"))
        }
    }

    @Test
    fun `configure forking in Java 8 VM`() {
        // given
        buildFile.appendText(
            """

            java {
                toolchain {
                    languageVersion.set(JavaLanguageVersion.of(8))
                }
            }
            """.trimIndent()
        )

        // when
        testProjectDir.buildWithArgsAndFail("compileJava").also { result ->
            // then
            result.assumeToolchainAvailable()
            assertThat(result.task(":compileJava")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
            assertThat(result.output).contains(FORKED)
            assertThat(result.output).containsMatch(JVM_ARG_BOOTCLASSPATH_ERRORPRONE_JAVAC)
            // Check that the configured jvm arg is preserved
            assertThat(result.output).contains(jvmArg("-XshowSettings"))
        }

        // check that it doesn't mess with task avoidance

        // when
        testProjectDir.buildWithArgsAndFail("compileJava").also { result ->
            // then
            result.assumeToolchainAvailable()
            assertThat(result.task(":compileJava")?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)
        }
    }

    @Test
    fun `configure forking in Java 16+ VM`() {
        // given
        buildFile.appendText(
            """

            java {
                toolchain {
                    languageVersion.set(JavaLanguageVersion.of(16))
                }
            }
            """.trimIndent()
        )

        // when
        testProjectDir.buildWithArgsAndFail("compileJava").also { result ->
            // then
            result.assumeToolchainAvailable()
            assertThat(result.task(":compileJava")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
            assertThat(result.output).contains(FORKED)
            assertThat(result.output).contains(JVM_ARGS_STRONG_ENCAPSULATION)
            // Check that the configured jvm arg is preserved
            assertThat(result.output).contains(jvmArg("-XshowSettings"))
        }

        // check that it doesn't mess with task avoidance

        // when
        testProjectDir.buildWithArgsAndFail("compileJava").also { result ->
            // then
            result.assumeToolchainAvailable()
            assertThat(result.task(":compileJava")?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)
        }
    }

    @Test
    fun `does not configure forking with JDK 8 if Error Prone is disabled`() {
        // given
        buildFile.appendText(
            """

            java {
                toolchain {
                    languageVersion.set(JavaLanguageVersion.of(8))
                }
            }

            tasks.compileJava { options.errorprone.isEnabled.set(false) }
            """.trimIndent()
        )

        // when
        val result = testProjectDir.buildWithArgsAndFail("compileJava")

        // then
        result.assumeToolchainAvailable()
        assertThat(result.task(":compileJava")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(result.output).contains(NOT_FORKED)
        assertThat(result.output).doesNotContain(JVM_ARG_BOOTCLASSPATH)
        assertThat(result.output).doesNotContain(JVM_ARGS_STRONG_ENCAPSULATION)
        // Check that the configured jvm arg is preserved
        assertThat(result.output).contains(jvmArg("-XshowSettings"))
    }

    @Test
    fun `does not configure forking with JDK 16+ if Error Prone is disabled`() {
        // https://github.com/gradle/gradle/issues/16857#issuecomment-931610187
        assume().that(GradleVersion.version(testGradleVersion)).isAtLeast(GradleVersion.version("7.0"))

        // given
        buildFile.appendText(
            """

            java {
                toolchain {
                    languageVersion.set(JavaLanguageVersion.of(17))
                }
            }

            tasks.compileJava { options.errorprone.isEnabled.set(false) }
            """.trimIndent()
        )

        // when
        val result = testProjectDir.buildWithArgsAndFail("compileJava")

        // then
        result.assumeToolchainAvailable()
        assertThat(result.task(":compileJava")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(result.output).contains(NOT_FORKED)
        assertThat(result.output).doesNotContain(JVM_ARG_BOOTCLASSPATH)
        assertThat(result.output).doesNotContain(JVM_ARGS_STRONG_ENCAPSULATION)
        // Check that the configured jvm arg is preserved
        assertThat(result.output).contains(jvmArg("-XshowSettings"))
    }

    @Test
    fun `configure bootclasspath for already-forked tasks`() {
        // given
        buildFile.appendText(
            """

            java {
                toolchain {
                    languageVersion.set(JavaLanguageVersion.of(8))
                }
            }

            tasks.compileJava {
                options.isFork = true
            }
            """.trimIndent()
        )

        // when
        val result = testProjectDir.buildWithArgsAndFail("compileJava")

        // then
        result.assumeToolchainAvailable()
        assertThat(result.task(":compileJava")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(result.output).contains(FORKED)
        assertThat(result.output).containsMatch(JVM_ARG_BOOTCLASSPATH_ERRORPRONE_JAVAC)
        // Check that the configured jvm arg is preserved
        assertThat(result.output).contains(jvmArg("-XshowSettings"))
    }
}
