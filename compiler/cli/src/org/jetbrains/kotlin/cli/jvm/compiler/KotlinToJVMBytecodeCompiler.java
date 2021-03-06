/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.cli.jvm.compiler;

import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.collect.Collections2;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.intellij.util.ArrayUtil;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function1;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.analyzer.AnalysisResult;
import org.jetbrains.kotlin.asJava.FilteredJvmDiagnostics;
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys;
import org.jetbrains.kotlin.cli.common.CompilerPlugin;
import org.jetbrains.kotlin.cli.common.CompilerPluginContext;
import org.jetbrains.kotlin.cli.common.messages.AnalyzerWithCompilerReport;
import org.jetbrains.kotlin.cli.common.messages.MessageCollector;
import org.jetbrains.kotlin.cli.common.output.outputUtils.OutputUtilsKt;
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler;
import org.jetbrains.kotlin.cli.jvm.config.JVMConfigurationKeys;
import org.jetbrains.kotlin.cli.jvm.config.JvmContentRootsKt;
import org.jetbrains.kotlin.cli.jvm.config.ModuleNameKt;
import org.jetbrains.kotlin.codegen.*;
import org.jetbrains.kotlin.codegen.state.GenerationState;
import org.jetbrains.kotlin.config.CompilerConfiguration;
import org.jetbrains.kotlin.config.ContentRootsKt;
import org.jetbrains.kotlin.context.ModuleContext;
import org.jetbrains.kotlin.fileClasses.JvmFileClassUtil;
import org.jetbrains.kotlin.idea.MainFunctionDetector;
import org.jetbrains.kotlin.load.kotlin.ModuleVisibilityManager;
import org.jetbrains.kotlin.load.kotlin.incremental.components.IncrementalCache;
import org.jetbrains.kotlin.load.kotlin.incremental.components.IncrementalCompilationComponents;
import org.jetbrains.kotlin.modules.JavaRootPath;
import org.jetbrains.kotlin.modules.Module;
import org.jetbrains.kotlin.modules.TargetId;
import org.jetbrains.kotlin.modules.TargetIdKt;
import org.jetbrains.kotlin.name.FqName;
import org.jetbrains.kotlin.progress.ProgressIndicatorAndCompilationCanceledStatus;
import org.jetbrains.kotlin.psi.KtFile;
import org.jetbrains.kotlin.psi.KtScript;
import org.jetbrains.kotlin.resolve.BindingTrace;
import org.jetbrains.kotlin.resolve.jvm.JvmClassName;
import org.jetbrains.kotlin.resolve.jvm.TopDownAnalyzerFacadeForJVM;
import org.jetbrains.kotlin.util.PerformanceCounter;
import org.jetbrains.kotlin.utils.ExceptionUtilsKt;
import org.jetbrains.kotlin.utils.KotlinPaths;

import java.io.File;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class KotlinToJVMBytecodeCompiler {

    private KotlinToJVMBytecodeCompiler() {
    }

    @NotNull
    private static List<String> getAbsolutePaths(@NotNull File directory, @NotNull Module module) {
        List<String> result = Lists.newArrayList();

        for (String sourceFile : module.getSourceFiles()) {
            File source = new File(sourceFile);
            if (!source.isAbsolute()) {
                source = new File(directory, sourceFile);
            }
            result.add(source.getAbsolutePath());
        }
        return result;
    }

    private static void writeOutput(
            @NotNull CompilerConfiguration configuration,
            @NotNull ClassFileFactory outputFiles,
            @Nullable File outputDir,
            @Nullable File jarPath,
            boolean jarRuntime,
            @Nullable FqName mainClass
    ) {
        if (jarPath != null) {
            CompileEnvironmentUtil.writeToJar(jarPath, jarRuntime, mainClass, outputFiles);
        }
        else {
            MessageCollector messageCollector = configuration.get(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE);
            OutputUtilsKt.writeAll(outputFiles, outputDir == null ? new File(".") : outputDir, messageCollector);
        }
    }

    public static boolean compileModules(
            @NotNull KotlinCoreEnvironment environment,
            @NotNull CompilerConfiguration configuration,
            @NotNull List<Module> chunk,
            @NotNull File directory,
            @Nullable File jarPath,
            @NotNull List<String> friendPaths,
            boolean jarRuntime
    ) {
        Map<Module, ClassFileFactory> outputFiles = Maps.newHashMap();

        ProgressIndicatorAndCompilationCanceledStatus.checkCanceled();

        ModuleVisibilityManager moduleVisibilityManager = ModuleVisibilityManager.SERVICE.getInstance(environment.getProject());

        for (Module module: chunk) {
            moduleVisibilityManager.addModule(module);
        }

        for (String path : friendPaths) {
            moduleVisibilityManager.addFriendPath(path);
        }

        String targetDescription = "in targets [" + Joiner.on(", ").join(Collections2.transform(chunk, new Function<Module, String>() {
            @Override
            public String apply(@Nullable Module input) {
                return input != null ? input.getModuleName() + "-" + input.getModuleType() : "<null>";
            }
        })) + "] ";
        AnalysisResult result = analyze(environment, targetDescription);
        if (result == null) {
            return false;
        }

        ProgressIndicatorAndCompilationCanceledStatus.checkCanceled();

        result.throwIfError();

        for (Module module : chunk) {
            ProgressIndicatorAndCompilationCanceledStatus.checkCanceled();
            List<KtFile> jetFiles = CompileEnvironmentUtil.getKtFiles(
                    environment.getProject(), getAbsolutePaths(directory, module), new Function1<String, Unit>() {
                        @Override
                        public Unit invoke(String s) {
                            throw new IllegalStateException("Should have been checked before: " + s);
                        }
                    }
            );
            File moduleOutputDirectory = new File(module.getOutputDirectory());
            GenerationState generationState =
                    generate(environment, result, jetFiles, module, moduleOutputDirectory,
                             module.getModuleName());
            outputFiles.put(module, generationState.getFactory());
        }

        for (Module module : chunk) {
            ProgressIndicatorAndCompilationCanceledStatus.checkCanceled();
            writeOutput(configuration, outputFiles.get(module), new File(module.getOutputDirectory()), jarPath, jarRuntime, null);
        }
        return true;
    }

    @NotNull
    public static CompilerConfiguration createCompilerConfiguration(
            @NotNull CompilerConfiguration base,
            @NotNull List<Module> chunk,
            @NotNull File directory
    ) {
        CompilerConfiguration configuration = base.copy();

        for (Module module : chunk) {
            ContentRootsKt.addKotlinSourceRoots(configuration, getAbsolutePaths(directory, module));
        }

        for (Module module : chunk) {
            for (JavaRootPath javaRootPath : module.getJavaSourceRoots()) {
                JvmContentRootsKt.addJavaSourceRoot(configuration, new File(javaRootPath.getPath()), javaRootPath.getPackagePrefix());
            }
        }

        for (Module module : chunk) {
            for (String classpathRoot : module.getClasspathRoots()) {
                JvmContentRootsKt.addJvmClasspathRoot(configuration, new File(classpathRoot));
            }
        }

        for (Module module : chunk) {
            configuration.add(JVMConfigurationKeys.MODULES, module);
        }

        return configuration;
    }

    @Nullable
    private static FqName findMainClass(@NotNull GenerationState generationState, @NotNull List<KtFile> files) {
        MainFunctionDetector mainFunctionDetector = new MainFunctionDetector(generationState.getBindingContext());
        FqName mainClass = null;
        for (KtFile file : files) {
            if (mainFunctionDetector.hasMain(file.getDeclarations())) {
                if (mainClass != null) {
                    // more than one main
                    return null;
                }
                FqName fqName = file.getPackageFqName();
                mainClass = JvmFileClassUtil.getFileClassInfoNoResolve(file).getFacadeClassFqName();
            }
        }
        return mainClass;
    }

    public static boolean compileBunchOfSources(
            @NotNull KotlinCoreEnvironment environment,
            @Nullable File jar,
            @Nullable File outputDir,
            @NotNull List<String> friendPaths,
            boolean includeRuntime
    ) {

        ModuleVisibilityManager moduleVisibilityManager = ModuleVisibilityManager.SERVICE.getInstance(environment.getProject());

        for (String path : friendPaths) {
            moduleVisibilityManager.addFriendPath(path);
        }

        GenerationState generationState = analyzeAndGenerate(environment);
        if (generationState == null) {
            return false;
        }

        FqName mainClass = findMainClass(generationState, environment.getSourceFiles());

        try {
            writeOutput(environment.getConfiguration(), generationState.getFactory(), outputDir, jar, includeRuntime, mainClass);
            return true;
        }
        finally {
            generationState.destroy();
        }
    }

    public static void compileAndExecuteScript(
            @NotNull CompilerConfiguration configuration,
            @NotNull KotlinPaths paths,
            @NotNull KotlinCoreEnvironment environment,
            @NotNull List<String> scriptArgs
    ) {
        Class<?> scriptClass = compileScript(configuration, paths, environment);
        if (scriptClass == null) return;
        Constructor<?> scriptConstructor = getScriptConstructor(scriptClass);

        try {
            scriptConstructor.newInstance(new Object[] {ArrayUtil.toStringArray(scriptArgs)});
        }
        catch (Throwable e) {
            reportExceptionFromScript(e);
        }
    }

    private static void reportExceptionFromScript(@NotNull  Throwable exception) {
        // expecting InvocationTargetException from constructor invocation with cause that describes the actual cause
        PrintStream stream = System.err;
        Throwable cause = exception.getCause();
        if (!(exception instanceof InvocationTargetException) || cause == null) {
            exception.printStackTrace(stream);
            return;
        }
        stream.println(cause);
        StackTraceElement[] fullTrace = cause.getStackTrace();
        int relevantEntries = fullTrace.length - exception.getStackTrace().length;
        for (int i = 0; i < relevantEntries; i++) {
            stream.println("\tat " + fullTrace[i]);
        }
    }

    @NotNull
    private static Constructor<?> getScriptConstructor(Class<?> scriptClass) {
        try {
            return scriptClass.getConstructor(String[].class);
        }
        catch (NoSuchMethodException e) {
            throw ExceptionUtilsKt.rethrow(e);
        }
    }

    @Nullable
    public static Class<?> compileScript(
            @NotNull CompilerConfiguration configuration,
            @NotNull KotlinPaths paths,
            @NotNull KotlinCoreEnvironment environment
    ) {
        GenerationState state = analyzeAndGenerate(environment);
        if (state == null) {
            return null;
        }

        GeneratedClassLoader classLoader;
        try {
            List<URL> classPaths = Lists.newArrayList(paths.getRuntimePath().toURI().toURL());
            for (File file : JvmContentRootsKt.getJvmClasspathRoots(configuration)) {
                classPaths.add(file.toURI().toURL());
            }
            //noinspection UnnecessaryFullyQualifiedName
            classLoader = new GeneratedClassLoader(state.getFactory(),
                                                   new URLClassLoader(classPaths.toArray(new URL[classPaths.size()]), null)
            );

            KtScript script = environment.getSourceFiles().get(0).getScript();
            assert script != null : "Script must be parsed";
            FqName nameForScript = script.getFqName();
            return classLoader.loadClass(nameForScript.asString());
        }
        catch (Exception e) {
            throw new RuntimeException("Failed to evaluate script: " + e, e);
        }
    }

    @Nullable
    public static GenerationState analyzeAndGenerate(@NotNull KotlinCoreEnvironment environment) {
        AnalysisResult result = analyze(environment, null);

        if (result == null) {
            return null;
        }

        if (!result.getShouldGenerateCode()) return null;

        result.throwIfError();

        return generate(environment, result, environment.getSourceFiles(), null, null, null);
    }

    @Nullable
    private static AnalysisResult analyze(@NotNull final KotlinCoreEnvironment environment, @Nullable String targetDescription) {
        MessageCollector collector = environment.getConfiguration().get(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY);
        assert collector != null;

        long analysisStart = PerformanceCounter.Companion.currentTime();
        AnalyzerWithCompilerReport analyzerWithCompilerReport = new AnalyzerWithCompilerReport(collector);
        analyzerWithCompilerReport.analyzeAndReport(
                environment.getSourceFiles(), new Function0<AnalysisResult>() {
                    @NotNull
                    @Override
                    public AnalysisResult invoke() {
                        BindingTrace sharedTrace = new CliLightClassGenerationSupport.NoScopeRecordCliBindingTrace();
                        ModuleContext moduleContext = TopDownAnalyzerFacadeForJVM.createContextWithSealedModule(environment.getProject(),
                                                                                                                ModuleNameKt
                                                                                                                        .getModuleName(environment));

                        return TopDownAnalyzerFacadeForJVM.analyzeFilesWithJavaIntegrationWithCustomContext(
                                moduleContext,
                                environment.getSourceFiles(),
                                sharedTrace,
                                environment.getConfiguration().get(JVMConfigurationKeys.MODULES),
                                environment.getConfiguration().get(JVMConfigurationKeys.INCREMENTAL_COMPILATION_COMPONENTS),
                                new JvmPackagePartProvider(environment)
                        );
                    }
                }
        );
        long analysisNanos = PerformanceCounter.Companion.currentTime() - analysisStart;
        String message = "ANALYZE: " + environment.getSourceFiles().size() + " files (" +
                         environment.getSourceLinesOfCode() + " lines) " +
                         (targetDescription != null ? targetDescription : "") +
                         "in " + TimeUnit.NANOSECONDS.toMillis(analysisNanos) + " ms";
        K2JVMCompiler.Companion.reportPerf(environment.getConfiguration(), message);

        AnalysisResult result = analyzerWithCompilerReport.getAnalysisResult();
        assert result != null : "AnalysisResult should be non-null, compiling: " + environment.getSourceFiles();

        CompilerPluginContext context = new CompilerPluginContext(environment.getProject(), result.getBindingContext(),
                                                                  environment.getSourceFiles());
        for (CompilerPlugin plugin : environment.getConfiguration().getList(CLIConfigurationKeys.COMPILER_PLUGINS)) {
            plugin.processFiles(context);
        }

        return analyzerWithCompilerReport.hasErrors() ? null : result;
    }

    @NotNull
    private static GenerationState generate(
            @NotNull KotlinCoreEnvironment environment,
            @NotNull AnalysisResult result,
            @NotNull List<KtFile> sourceFiles,
            @Nullable Module module,
            File outputDirectory,
            String moduleName
    ) {
        CompilerConfiguration configuration = environment.getConfiguration();
        IncrementalCompilationComponents incrementalCompilationComponents = configuration.get(JVMConfigurationKeys.INCREMENTAL_COMPILATION_COMPONENTS);

        Collection<FqName> packagesWithObsoleteParts;
        List<FqName> obsoleteMultifileClasses;
        TargetId targetId = null;

        if (module == null || incrementalCompilationComponents == null) {
            packagesWithObsoleteParts = Collections.emptySet();
            obsoleteMultifileClasses = Collections.emptyList();
        }
        else {
            targetId = TargetIdKt.TargetId(module);
            IncrementalCache incrementalCache = incrementalCompilationComponents.getIncrementalCache(targetId);

            packagesWithObsoleteParts = new HashSet<FqName>();
            for (String internalName : incrementalCache.getObsoletePackageParts()) {
                packagesWithObsoleteParts.add(JvmClassName.byInternalName(internalName).getPackageFqName());
            }

            obsoleteMultifileClasses = new ArrayList<FqName>();
            for (String obsoleteFacadeInternalName : incrementalCache.getObsoleteMultifileClasses()) {
                obsoleteMultifileClasses.add(JvmClassName.byInternalName(obsoleteFacadeInternalName).getFqNameForClassNameWithoutDollars());
            }
        }
        GenerationState generationState = new GenerationState(
                environment.getProject(),
                ClassBuilderFactories.BINARIES,
                result.getModuleDescriptor(),
                result.getBindingContext(),
                sourceFiles,
                configuration.get(JVMConfigurationKeys.DISABLE_CALL_ASSERTIONS, false),
                configuration.get(JVMConfigurationKeys.DISABLE_PARAM_ASSERTIONS, false),
                GenerationState.GenerateClassFilter.GENERATE_ALL,
                configuration.get(JVMConfigurationKeys.DISABLE_INLINE, false),
                configuration.get(JVMConfigurationKeys.DISABLE_OPTIMIZATION, false),
                /* useTypeTableInSerializer = */ false,
                packagesWithObsoleteParts,
                obsoleteMultifileClasses,
                targetId,
                moduleName,
                outputDirectory,
                incrementalCompilationComponents,
                configuration.get(JVMConfigurationKeys.MULTIFILE_FACADES_OPEN, false)
        );
        ProgressIndicatorAndCompilationCanceledStatus.checkCanceled();

        long generationStart = PerformanceCounter.Companion.currentTime();

        KotlinCodegenFacade.compileCorrectFiles(generationState, CompilationErrorHandler.THROW_EXCEPTION);

        long generationNanos = PerformanceCounter.Companion.currentTime() - generationStart;
        String desc = module != null ? "target " + module.getModuleName() + "-" + module.getModuleType() + " " : "";
        String message = "GENERATE: " + sourceFiles.size() + " files (" +
                         environment.countLinesOfCode(sourceFiles) + " lines) " + desc + "in " + TimeUnit.NANOSECONDS.toMillis(generationNanos) + " ms";
        K2JVMCompiler.Companion.reportPerf(environment.getConfiguration(), message);
        ProgressIndicatorAndCompilationCanceledStatus.checkCanceled();

        AnalyzerWithCompilerReport.reportDiagnostics(
                new FilteredJvmDiagnostics(
                        generationState.getCollectedExtraJvmDiagnostics(),
                        result.getBindingContext().getDiagnostics()
                ),
                environment.getConfiguration().get(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY)
        );
        ProgressIndicatorAndCompilationCanceledStatus.checkCanceled();
        return generationState;
    }
}
