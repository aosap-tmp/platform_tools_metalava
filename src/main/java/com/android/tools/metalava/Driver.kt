/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
@file:JvmName("Driver")

package com.android.tools.metalava

import com.android.SdkConstants
import com.android.SdkConstants.DOT_JAVA
import com.android.SdkConstants.DOT_KT
import com.android.ide.common.process.CachedProcessOutputHandler
import com.android.ide.common.process.DefaultProcessExecutor
import com.android.ide.common.process.ProcessInfoBuilder
import com.android.ide.common.process.ProcessOutput
import com.android.ide.common.process.ProcessOutputHandler
import com.android.tools.lint.KotlinLintAnalyzerFacade
import com.android.tools.lint.LintCoreApplicationEnvironment
import com.android.tools.lint.LintCoreProjectEnvironment
import com.android.tools.lint.annotations.Extractor
import com.android.tools.lint.checks.infrastructure.ClassName
import com.android.tools.lint.detector.api.assertionsEnabled
import com.android.tools.metalava.apilevels.ApiGenerator
import com.android.tools.metalava.doclava1.ApiFile
import com.android.tools.metalava.doclava1.ApiParseException
import com.android.tools.metalava.doclava1.ApiPredicate
import com.android.tools.metalava.doclava1.ElidingPredicate
import com.android.tools.metalava.doclava1.Errors
import com.android.tools.metalava.doclava1.FilterPredicate
import com.android.tools.metalava.model.Codebase
import com.android.tools.metalava.model.Item
import com.android.tools.metalava.model.PackageDocs
import com.android.tools.metalava.model.psi.PsiBasedCodebase
import com.android.tools.metalava.model.visitors.ApiVisitor
import com.android.utils.StdLogger
import com.android.utils.StdLogger.Level.ERROR
import com.google.common.base.Stopwatch
import com.google.common.collect.Lists
import com.google.common.io.Files
import com.intellij.openapi.diagnostic.DefaultLogger
import com.intellij.openapi.roots.LanguageLevelProjectExtension
import com.intellij.openapi.util.Disposer
import com.intellij.util.execution.ParametersListUtil
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeUnit.SECONDS
import java.util.function.Predicate
import kotlin.text.Charsets.UTF_8

const val PROGRAM_NAME = "metalava"
const val HELP_PROLOGUE = "$PROGRAM_NAME extracts metadata from source code to generate artifacts such as the " +
    "signature files, the SDK stub files, external annotations etc."

@Suppress("PropertyName") // Can't mark const because trimIndent() :-(
val BANNER: String = """
                _        _
 _ __ ___   ___| |_ __ _| | __ ___   ____ _
| '_ ` _ \ / _ \ __/ _` | |/ _` \ \ / / _` |
| | | | | |  __/ || (_| | | (_| |\ V / (_| |
|_| |_| |_|\___|\__\__,_|_|\__,_| \_/ \__,_|
""".trimIndent()

fun main(args: Array<String>) {
    run(args, setExitCode = true)
}

/**
 * The metadata driver is a command line interface to extracting various metadata
 * from a source tree (or existing signature files etc). Run with --help to see
 * more details.
 */
fun run(
    args: Array<String>,
    stdout: PrintWriter = PrintWriter(OutputStreamWriter(System.out)),
    stderr: PrintWriter = PrintWriter(OutputStreamWriter(System.err)),
    setExitCode: Boolean = false
): Boolean {

    if (System.getenv(ENV_VAR_METALAVA_DUMP_ARGV) != null &&
        !java.lang.Boolean.getBoolean(ENV_VAR_METALAVA_TESTS_RUNNING)
    ) {
        stdout.println("---Running $PROGRAM_NAME----")
        stdout.println("pwd=${File("").absolutePath}")
        args.forEach { arg ->
            stdout.println("\"$arg\",")
        }
        stdout.println("----------------------------")
    }

    try {
        val modifiedArgs =
            if (args.isEmpty()) {
                arrayOf("--help")
            } else {
                val prepend = envVarToArgs(ENV_VAR_METALAVA_PREPEND_ARGS)
                val append = envVarToArgs(ENV_VAR_METALAVA_APPEND_ARGS)
                prepend + args + append
            }

        compatibility = Compatibility(compat = Options.useCompatMode(args))
        options = Options(modifiedArgs, stdout, stderr)
        processFlags()
        stdout.flush()
        stderr.flush()

        if (setExitCode && reporter.hasErrors()) {
            exit(-1)
        }
        return true
    } catch (e: DriverException) {
        if (e.stderr.isNotBlank()) {
            stderr.println("\n${e.stderr}")
        }
        if (e.stdout.isNotBlank()) {
            stdout.println("\n${e.stdout}")
        }
        stderr.flush()
        stdout.flush()
        if (setExitCode) { // always true in production; not set from tests
            exit(e.exitCode)
        }
    }
    stdout.flush()
    stderr.flush()
    return false
}

/**
 * Given an environment variable name pointing to a shell argument string,
 * returns the parsed argument strings (or empty array if not set)
 */
private fun envVarToArgs(varName: String): Array<String> {
    val value = System.getenv(varName) ?: return emptyArray()
    return ParametersListUtil.parse(value).toTypedArray()
}

private fun exit(exitCode: Int = 0) {
    if (options.verbose) {
        options.stdout.println("$PROGRAM_NAME exiting with exit code $exitCode")
    }
    options.stdout.flush()
    options.stderr.flush()
    System.exit(exitCode)
}

private fun processFlags() {
    val stopwatch = Stopwatch.createStarted()

    // --copy-annotations?
    val privateAnnotationsSource = options.privateAnnotationsSource
    val privateAnnotationsTarget = options.privateAnnotationsTarget
    if (privateAnnotationsSource != null && privateAnnotationsTarget != null) {
        val rewrite = RewriteAnnotations()
        // Support pointing to both stub-annotations and stub-annotations/src/main/java
        val src = File(privateAnnotationsSource, "src${File.separator}main${File.separator}java")
        val source = if (src.isDirectory) src else privateAnnotationsSource
        source.listFiles()?.forEach { file ->
            rewrite.modifyAnnotationSources(null, file, File(privateAnnotationsTarget, file.name))
        }
    }

    // --rewrite-annotations?
    options.rewriteAnnotations?.let { RewriteAnnotations().rewriteAnnotations(it) }

    val codebase =
        if (options.sources.size == 1 && options.sources[0].path.endsWith(SdkConstants.DOT_TXT)) {
            loadFromSignatureFiles(
                file = options.sources[0], kotlinStyleNulls = options.inputKotlinStyleNulls,
                manifest = options.manifest, performChecks = true, supportsStagedNullability = true
            )
        } else if (options.apiJar != null) {
            loadFromJarFile(options.apiJar!!)
        } else if (options.sources.size == 1 && options.sources[0].path.endsWith(SdkConstants.DOT_JAR)) {
            loadFromJarFile(options.sources[0])
        } else if (options.sources.isNotEmpty() || options.sourcePath.isNotEmpty()) {
            loadFromSources()
        } else {
            return
        }
    options.manifest?.let { codebase.manifest = it }

    if (options.verbose) {
        options.stdout.println("\n$PROGRAM_NAME analyzed API in ${stopwatch.elapsed(TimeUnit.SECONDS)} seconds")
    }

    val androidApiLevelXml = options.generateApiLevelXml
    val apiLevelJars = options.apiLevelJars
    if (androidApiLevelXml != null && apiLevelJars != null) {
        progress("\nGenerating API levels XML descriptor file, ${androidApiLevelXml.name}: ")
        ApiGenerator.generate(apiLevelJars, androidApiLevelXml, codebase)
    }

    if ((options.stubsDir != null || options.docStubsDir != null) && codebase.supportsDocumentation()) {
        progress("\nEnhancing docs: ")
        val docAnalyzer = DocAnalyzer(codebase)
        docAnalyzer.enhance()

        val applyApiLevelsXml = options.applyApiLevelsXml
        if (applyApiLevelsXml != null) {
            progress("\nApplying API levels")
            docAnalyzer.applyApiLevels(applyApiLevelsXml)
        }
    }

    // Generate the documentation stubs *before* we migrate nullness information.
    options.docStubsDir?.let {
        createStubFiles(
            it, codebase, docStubs = true,
            writeStubList = options.docStubsSourceList != null
        )
    }

    val currentApiFile = options.currentApi
    if (currentApiFile != null && options.checkCompatibility) {
        val current =
            if (currentApiFile.path.endsWith(SdkConstants.DOT_JAR)) {
                loadFromJarFile(currentApiFile)
            } else {
                loadFromSignatureFiles(
                    currentApiFile, options.inputKotlinStyleNulls,
                    supportsStagedNullability = true
                )
            }

        // If configured, compares the new API with the previous API and reports
        // any incompatibilities.
        CompatibilityCheck.checkCompatibility(codebase, current)
    }

    val previousApiFile = options.previousApi
    if (previousApiFile != null) {
        val previous =
            if (previousApiFile.path.endsWith(SdkConstants.DOT_JAR)) {
                loadFromJarFile(previousApiFile)
            } else {
                loadFromSignatureFiles(
                    previousApiFile, options.inputKotlinStyleNulls,
                    supportsStagedNullability = true
                )
            }

        // If configured, checks for newly added nullness information compared
        // to the previous stable API and marks the newly annotated elements
        // as migrated (which will cause the Kotlin compiler to treat problems
        // as warnings instead of errors

        migrateNulls(codebase, previous)

        previous.dispose()
    }

    // Based on the input flags, generates various output files such
    // as signature files and/or stubs files
    options.apiFile?.let { apiFile ->
        val apiFilter = FilterPredicate(ApiPredicate(codebase))
        val apiReference = ApiPredicate(codebase, ignoreShown = true)
        val apiEmit = apiFilter.and(ElidingPredicate(apiReference))

        createReportFile(codebase, apiFile, "API") { printWriter ->
            val preFiltered = codebase.original != null
            SignatureWriter(printWriter, apiEmit, apiReference, preFiltered)
        }
    }

    options.dexApiFile?.let { apiFile ->
        val apiFilter = FilterPredicate(ApiPredicate(codebase))
        val memberIsNotCloned: Predicate<Item> = Predicate { !it.isCloned() }
        val apiReference = ApiPredicate(codebase, ignoreShown = true)
        val dexApiEmit = memberIsNotCloned.and(apiFilter)

        createReportFile(
            codebase, apiFile, "DEX API"
        ) { printWriter -> DexApiWriter(printWriter, dexApiEmit, apiReference) }
    }

    options.removedApiFile?.let { apiFile ->
        val unfiltered = codebase.original ?: codebase

        val removedFilter = FilterPredicate(ApiPredicate(codebase, matchRemoved = true))
        val removedReference = ApiPredicate(codebase, ignoreShown = true, ignoreRemoved = true)
        val removedEmit = removedFilter.and(ElidingPredicate(removedReference))

        createReportFile(unfiltered, apiFile, "removed API") { printWriter ->
            SignatureWriter(printWriter, removedEmit, removedReference, codebase.original != null)
        }
    }

    options.removedDexApiFile?.let { apiFile ->
        val unfiltered = codebase.original ?: codebase

        val removedFilter = FilterPredicate(ApiPredicate(codebase, matchRemoved = true))
        val removedReference = ApiPredicate(codebase, ignoreShown = true, ignoreRemoved = true)
        val memberIsNotCloned: Predicate<Item> = Predicate { !it.isCloned() }
        val removedDexEmit = memberIsNotCloned.and(removedFilter)

        createReportFile(
            unfiltered, apiFile, "removed DEX API"
        ) { printWriter -> DexApiWriter(printWriter, removedDexEmit, removedReference) }
    }

    options.privateApiFile?.let { apiFile ->
        val apiFilter = FilterPredicate(ApiPredicate(codebase))
        val memberIsNotCloned: Predicate<Item> = Predicate { !it.isCloned() }
        val privateEmit = memberIsNotCloned.and(apiFilter.negate())
        val privateReference = Predicate<Item> { true }

        createReportFile(codebase, apiFile, "private API") { printWriter ->
            SignatureWriter(printWriter, privateEmit, privateReference, codebase.original != null)
        }
    }

    options.privateDexApiFile?.let { apiFile ->
        val apiFilter = FilterPredicate(ApiPredicate(codebase))
        val privateEmit = apiFilter.negate()
        val privateReference = Predicate<Item> { true }

        createReportFile(
            codebase, apiFile, "private DEX API"
        ) { printWriter ->
            DexApiWriter(
                printWriter, privateEmit, privateReference, inlineInheritedFields = false
            )
        }
    }

    options.proguard?.let { proguard ->
        val apiEmit = FilterPredicate(ApiPredicate(codebase))
        val apiReference = ApiPredicate(codebase, ignoreShown = true)
        createReportFile(
            codebase, proguard, "Proguard file"
        ) { printWriter -> ProguardWriter(printWriter, apiEmit, apiReference) }
    }

    options.sdkValueDir?.let { dir ->
        dir.mkdirs()
        SdkFileWriter(codebase, dir).generate()
    }

    // Now that we've migrated nullness information we can proceed to write non-doc stubs, if any.

    options.stubsDir?.let {
        createStubFiles(
            it, codebase, docStubs = false,
            writeStubList = options.stubsSourceList != null
        )

        val stubAnnotations = options.copyStubAnnotationsFrom
        if (stubAnnotations != null) {
            // Support pointing to both stub-annotations and stub-annotations/src/main/java
            val src = File(stubAnnotations, "src${File.separator}main${File.separator}java")
            val source = if (src.isDirectory) src else stubAnnotations
            source.listFiles()?.forEach { file ->
                RewriteAnnotations().copyAnnotations(codebase, file, File(it, file.name))
            }
        }
    }

    if (options.docStubsDir == null && options.stubsDir == null) {
        val writeStubsFile: (File) -> Unit = { file ->
            val root = File("").absoluteFile
            val sources = options.sources
            val rootPath = root.path
            val contents = sources.joinToString(" ") {
                val path = it.path
                if (path.startsWith(rootPath)) {
                    path.substring(rootPath.length)
                } else {
                    path
                }
            }
            Files.asCharSink(file, UTF_8).write(contents)
        }
        options.stubsSourceList?.let(writeStubsFile)
        options.docStubsSourceList?.let(writeStubsFile)
    }
    options.externalAnnotations?.let { extractAnnotations(codebase, it) }
    progress("\n")

    // Coverage stats?
    if (options.dumpAnnotationStatistics) {
        progress("\nMeasuring annotation statistics: ")
        AnnotationStatistics(codebase).count()
    }
    if (options.annotationCoverageOf.isNotEmpty()) {
        progress("\nMeasuring annotation coverage: ")
        AnnotationStatistics(codebase).measureCoverageOf(options.annotationCoverageOf)
    }

    Disposer.dispose(LintCoreApplicationEnvironment.get().parentDisposable)

    if (!options.quiet) {
        val packageCount = codebase.size()
        options.stdout.println("\n$PROGRAM_NAME finished handling $packageCount packages in $stopwatch")
        options.stdout.flush()
    }

    invokeDocumentationTool()
}

fun invokeDocumentationTool() {
    if (options.noDocs) {
        return
    }

    val args = options.invokeDocumentationToolArguments
    if (args.isNotEmpty()) {
        if (!options.quiet) {
            options.stdout.println(
                "Invoking external documentation tool ${args[0]} with arguments\n\"${
                args.slice(1 until args.size).joinToString(separator = "\",\n\"") { it }}\""
            )
            options.stdout.flush()
        }

        val builder = ProcessInfoBuilder()

        builder.setExecutable(File(args[0]))
        builder.addArgs(args.slice(1 until args.size))

        val processOutputHandler =
            if (options.quiet) {
                CachedProcessOutputHandler()
            } else {
                object : ProcessOutputHandler {
                    override fun handleOutput(processOutput: ProcessOutput?) {
                    }

                    override fun createOutput(): ProcessOutput {
                        return object : ProcessOutput {
                            override fun getStandardOutput(): OutputStream {
                                return System.out
                            }

                            override fun getErrorOutput(): OutputStream {
                                return System.err
                            }

                            override fun close() {
                                System.out.flush()
                                System.err.flush()
                            }
                        }
                    }
                }
            }

        val result = DefaultProcessExecutor(StdLogger(ERROR))
            .execute(builder.createProcess(), processOutputHandler)

        val exitCode = result.exitValue
        if (!options.quiet) {
            options.stdout.println("${args[0]} finished with exitCode $exitCode")
            options.stdout.flush()
        }
        if (exitCode != 0) {
            val stdout = if (processOutputHandler is CachedProcessOutputHandler)
                processOutputHandler.processOutput.errorOutputAsString
            else ""
            val stderr = if (processOutputHandler is CachedProcessOutputHandler)
                processOutputHandler.processOutput.errorOutputAsString
            else ""
            throw DriverException(
                stdout = "Invoking documentation tool ${args[0]} failed with exit code $exitCode\n$stdout",
                stderr = stderr,
                exitCode = exitCode
            )
        }
    }
}

private fun migrateNulls(codebase: Codebase, previous: Codebase) {
    if (options.migrateNulls) {
        val codebaseSupportsNullability = previous.supportsStagedNullability
        val prevSupportsNullability = previous.supportsStagedNullability
        try {
            previous.supportsStagedNullability = true
            codebase.supportsStagedNullability = true
            previous.compareWith(
                NullnessMigration(), codebase,
                ApiPredicate(codebase)
            )
        } finally {
            previous.supportsStagedNullability = prevSupportsNullability
            codebase.supportsStagedNullability = codebaseSupportsNullability
        }
    }
}

private fun loadFromSignatureFiles(
    file: File,
    kotlinStyleNulls: Boolean,
    manifest: File? = null,
    performChecks: Boolean = false,
    supportsStagedNullability: Boolean = false
): Codebase {
    try {
        val codebase = ApiFile.parseApi(File(file.path), kotlinStyleNulls, supportsStagedNullability)
        codebase.manifest = manifest
        codebase.description = "Codebase loaded from ${file.name}"

        if (performChecks) {
            val analyzer = ApiAnalyzer(codebase)
            analyzer.performChecks()
        }
        return codebase
    } catch (ex: ApiParseException) {
        val message = "Unable to parse signature file $file: ${ex.message}"
        throw DriverException(message)
    }
}

private fun loadFromSources(): Codebase {
    progress("\nProcessing sources: ")

    val sources = if (options.sources.isEmpty()) {
        if (options.verbose) {
            options.stdout.println("No source files specified: recursively including all sources found in the source path")
        }
        gatherSources(options.sourcePath)
    } else {
        options.sources
    }

    progress("\nReading Codebase: ")
    val codebase = parseSources(sources, "Codebase loaded from source folders")

    progress("\nAnalyzing API: ")

    val analyzer = ApiAnalyzer(codebase)
    analyzer.mergeExternalAnnotations()
    analyzer.computeApi()
    analyzer.handleStripping()

    if (options.checkKotlinInterop) {
        KotlinInteropChecks().check(codebase)
    }

    // General API checks for Android APIs
    AndroidApiChecks().check(codebase)

    val filterEmit = ApiPredicate(codebase, ignoreShown = true, ignoreRemoved = false)
    val apiEmit = ApiPredicate(codebase, ignoreShown = true)
    val apiReference = ApiPredicate(codebase, ignoreShown = true)

    // Copy methods from soon-to-be-hidden parents into descendant classes, when necessary
    progress("\nInsert missing stubs methods: ")
    analyzer.generateInheritedStubs(apiEmit, apiReference)

    // Compute default constructors (and add missing package private constructors
    // to make stubs compilable if necessary)
    if (options.stubsDir != null || options.docStubsDir != null) {
        progress("\nInsert missing constructors: ")
        analyzer.addConstructors(filterEmit)
    }

    progress("\nPerforming misc API checks: ")
    analyzer.performChecks()

    return codebase
}

/**
 * Returns a codebase initialized from the given Java or Kotlin source files, with the given
 * description. The codebase will use a project environment initialized according to the current
 * [options].
 */
internal fun parseSources(sources: List<File>, description: String): PsiBasedCodebase {
    val projectEnvironment = createProjectEnvironment()
    val project = projectEnvironment.project

    // Push language level to PSI handler
    project.getComponent(LanguageLevelProjectExtension::class.java)?.languageLevel =
        options.javaLanguageLevel

    val joined = mutableListOf<File>()
    joined.addAll(options.sourcePath.map { it.absoluteFile })
    joined.addAll(options.classpath.map { it.absoluteFile })
    // Add in source roots implied by the source files
    extractRoots(options.sources, joined)

    // Create project environment with those paths
    projectEnvironment.registerPaths(joined)

    val kotlinFiles = sources.filter { it.path.endsWith(SdkConstants.DOT_KT) }
    val trace = KotlinLintAnalyzerFacade().analyze(kotlinFiles, joined, project)

    val units = Extractor.createUnitsForFiles(project, sources)
    val packageDocs = gatherHiddenPackagesFromJavaDocs(options.sourcePath)

    val codebase = PsiBasedCodebase(description)
    codebase.initialize(project, units, packageDocs)
    codebase.manifest = options.manifest
    codebase.apiLevel = options.currentApiLevel
    codebase.bindingContext = trace.bindingContext
    return codebase
}

private fun loadFromJarFile(apiJar: File, manifest: File? = null): Codebase {
    val projectEnvironment = createProjectEnvironment()

    progress("Processing jar file: ")

    // Create project environment with those paths
    val project = projectEnvironment.project
    projectEnvironment.registerPaths(listOf(apiJar))

    val kotlinFiles = emptyList<File>()
    val trace = KotlinLintAnalyzerFacade().analyze(kotlinFiles, listOf(apiJar), project)

    val codebase = PsiBasedCodebase()
    codebase.description = "Codebase loaded from $apiJar"
    codebase.initialize(project, apiJar)
    if (manifest != null) {
        codebase.manifest = options.manifest
    }
    val analyzer = ApiAnalyzer(codebase)
    analyzer.mergeExternalAnnotations()
    codebase.bindingContext = trace.bindingContext
    return codebase
}

private fun createProjectEnvironment(): LintCoreProjectEnvironment {
    ensurePsiFileCapacity()
    val appEnv = LintCoreApplicationEnvironment.get()
    val parentDisposable = Disposer.newDisposable()

    if (!assertionsEnabled() &&
        System.getenv(ENV_VAR_METALAVA_DUMP_ARGV) == null &&
        !java.lang.Boolean.getBoolean(ENV_VAR_METALAVA_TESTS_RUNNING)
    ) {
        DefaultLogger.disableStderrDumping(parentDisposable)
    }

    return LintCoreProjectEnvironment.create(parentDisposable, appEnv)
}

private fun ensurePsiFileCapacity() {
    val fileSize = System.getProperty("idea.max.intellisense.filesize")
    if (fileSize == null) {
        // Ensure we can handle large compilation units like android.R
        System.setProperty("idea.max.intellisense.filesize", "100000")
    }
}

private fun extractAnnotations(codebase: Codebase, file: File) {
    val localTimer = Stopwatch.createStarted()

    options.externalAnnotations?.let { outputFile ->
        @Suppress("UNCHECKED_CAST")
        ExtractAnnotations(
            codebase,
            outputFile
        ).extractAnnotations()
        if (options.verbose) {
            options.stdout.print("\n$PROGRAM_NAME extracted annotations into $file in $localTimer")
            options.stdout.flush()
        }
    }
}

private fun createStubFiles(stubDir: File, codebase: Codebase, docStubs: Boolean, writeStubList: Boolean) {
    // Generating stubs from a sig-file-based codebase is problematic
    assert(codebase.supportsDocumentation())

    if (docStubs) {
        progress("\nGenerating documentation stub files: ")
    } else {
        progress("\nGenerating stub files: ")
    }

    val localTimer = Stopwatch.createStarted()
    val prevCompatibility = compatibility
    if (compatibility.compat) {
        // if (!options.quiet) {
        //    options.stderr.println("Warning: Turning off compat mode when generating stubs")
        // }
        compatibility = Compatibility(false)
        // But preserve the setting for whether we want to erase throws signatures (to ensure the API
        // stays compatible)
        compatibility.useErasureInThrows = prevCompatibility.useErasureInThrows
    }

    // Fow now, we don't put annotations into documentation stub files, unless you're explicitly
    // generating only documentation stubs *and* asked to include annotations
    val generateAnnotations = options.generateAnnotations && (!docStubs || options.stubsDir == null)

    val stubWriter =
        StubWriter(
            codebase = codebase,
            stubsDir = stubDir,
            generateAnnotations = generateAnnotations,
            preFiltered = codebase.original != null,
            docStubs = docStubs
        )
    codebase.accept(stubWriter)

    if (docStubs) {
        // Overview docs? These are generally in the empty package.
        codebase.findPackage("")?.let { empty ->
            val overview = codebase.getPackageDocs()?.getOverviewDocumentation(empty)
            if (overview != null && overview.isNotBlank()) {
                stubWriter.writeDocOverview(empty, overview)
            }
        }
    }

    if (writeStubList) {
        // Optionally also write out a list of source files that were generated; used
        // for example to point javadoc to the stubs output to generate documentation
        val file = if (docStubs) {
            options.docStubsSourceList ?: options.stubsSourceList
        } else {
            options.stubsSourceList
        }
        file?.let {
            val root = File("").absoluteFile
            stubWriter.writeSourceList(it, root)
        }
    }

    compatibility = prevCompatibility

    progress(
        "\n$PROGRAM_NAME wrote ${if (docStubs) "documentation" else ""} stubs directory $stubDir in ${
        localTimer.elapsed(SECONDS)} seconds"
    )
}

fun progress(message: String) {
    if (options.verbose) {
        options.stdout.print(message)
        options.stdout.flush()
    }
}

private fun createReportFile(
    codebase: Codebase,
    apiFile: File,
    description: String,
    createVisitor: (PrintWriter) -> ApiVisitor
) {
    progress("\nWriting $description file: ")
    val localTimer = Stopwatch.createStarted()
    try {
        val writer = PrintWriter(Files.asCharSink(apiFile, Charsets.UTF_8).openBufferedStream())
        writer.use { printWriter ->
            val apiWriter = createVisitor(printWriter)
            codebase.accept(apiWriter)
        }
    } catch (e: IOException) {
        reporter.report(Errors.IO_ERROR, apiFile, "Cannot open file for write.")
    }
    if (options.verbose) {
        options.stdout.print("\n$PROGRAM_NAME wrote $description file $apiFile in ${localTimer.elapsed(SECONDS)} seconds")
    }
}

/** Used for verbose output to show progress bar */
private var tick = 0

/** Needed for tests to ensure we don't get unpredictable behavior of "." in output */
fun resetTicker() {
    tick = 0
}

/** Print progress */
fun tick() {
    tick++
    if (tick % 100 == 0) {
        if (!options.verbose) {
            return
        }
        options.stdout.print(".")
        options.stdout.flush()
    }
}

private fun addSourceFiles(list: MutableList<File>, file: File) {
    if (file.isDirectory) {
        val files = file.listFiles()
        if (files != null) {
            for (child in files) {
                addSourceFiles(list, child)
            }
        }
    } else {
        if (file.isFile && (file.path.endsWith(DOT_JAVA) || file.path.endsWith(DOT_KT))) {
            list.add(file)
        }
    }
}

fun gatherSources(sourcePath: List<File>): List<File> {
    val sources = Lists.newArrayList<File>()
    for (file in sourcePath) {
        addSourceFiles(sources, file.absoluteFile)
    }
    return sources
}

private fun addHiddenPackages(
    packageToDoc: MutableMap<String, String>,
    packageToOverview: MutableMap<String, String>,
    hiddenPackages: MutableSet<String>,
    file: File,
    pkg: String
) {
    if (file.isDirectory) {
        val files = file.listFiles()
        if (files != null) {
            for (child in files) {
                val subPkg =
                    if (child.isDirectory)
                        if (pkg.isEmpty())
                            child.name
                        else pkg + "." + child.name
                    else pkg
                addHiddenPackages(packageToDoc, packageToOverview, hiddenPackages, child, subPkg)
            }
        }
    } else if (file.isFile) {
        val map = when {
            file.name == "package.html" -> packageToDoc
            file.name == "overview.html" -> {
                packageToOverview
            }
            else -> return
        }
        val contents = Files.asCharSource(file, Charsets.UTF_8).read()
        map[pkg] = contents
        if (contents.contains("@hide")) {
            hiddenPackages.add(pkg)
        }
    }
}

private fun gatherHiddenPackagesFromJavaDocs(sourcePath: List<File>): PackageDocs {
    val packageHtml = HashMap<String, String>(100)
    val overviewHtml = HashMap<String, String>(10)
    val set = HashSet<String>(100)
    for (file in sourcePath) {
        addHiddenPackages(packageHtml, overviewHtml, set, file, "")
    }
    return PackageDocs(packageHtml, overviewHtml, set)
}

private fun extractRoots(sources: List<File>, sourceRoots: MutableList<File> = mutableListOf()): List<File> {
    // Cache for each directory since computing root for a source file is
    // expensive
    val dirToRootCache = mutableMapOf<String, File>()
    for (file in sources) {
        val parent = file.parentFile ?: continue
        val found = dirToRootCache[parent.path]
        if (found != null) {
            continue
        }

        val root = findRoot(file) ?: continue
        dirToRootCache[parent.path] = root

        if (!sourceRoots.contains(root)) {
            sourceRoots.add(root)
        }
    }

    return sourceRoots
}

/**
 * If given a full path to a Java or Kotlin source file, produces the path to
 * the source root if possible.
 */
private fun findRoot(file: File): File? {
    val path = file.path
    if (path.endsWith(DOT_JAVA) || path.endsWith(DOT_KT)) {
        val pkg = findPackage(file) ?: return null
        val parent = file.parentFile ?: return null
        val endIndex = parent.path.length - pkg.length
        val before = path[endIndex - 1]
        if (before == '/' || before == '\\') {
            return File(path.substring(0, endIndex))
        } else {
            reporter.report(Errors.IO_ERROR, file, "$PROGRAM_NAME was unable to determine the package name")
        }
    }

    return null
}

/** Finds the package of the given Java/Kotlin source file, if possible */
fun findPackage(file: File): String? {
    val source = Files.asCharSource(file, Charsets.UTF_8).read()
    return findPackage(source)
}

/** Finds the package of the given Java/Kotlin source code, if possible */
fun findPackage(source: String): String? {
    return ClassName(source).packageName
}
