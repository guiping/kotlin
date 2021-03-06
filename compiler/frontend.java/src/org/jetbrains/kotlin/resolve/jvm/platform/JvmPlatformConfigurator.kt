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

package org.jetbrains.kotlin.resolve.jvm.platform

import org.jetbrains.kotlin.container.StorageComponentContainer
import org.jetbrains.kotlin.container.useImpl
import org.jetbrains.kotlin.jvm.RuntimeAssertionsTypeChecker
import org.jetbrains.kotlin.load.kotlin.JavaAnnotationCallChecker
import org.jetbrains.kotlin.load.kotlin.nativeDeclarations.NativeFunChecker
import org.jetbrains.kotlin.resolve.*
import org.jetbrains.kotlin.resolve.jvm.JvmOverloadFilter
import org.jetbrains.kotlin.resolve.jvm.checkers.*
import org.jetbrains.kotlin.synthetic.JavaSyntheticScopes
import org.jetbrains.kotlin.types.DynamicTypesSettings


object JvmPlatformConfigurator : PlatformConfigurator(
        DynamicTypesSettings(),
        additionalDeclarationCheckers = listOf(
                PlatformStaticAnnotationChecker(),
                JvmNameAnnotationChecker(),
                VolatileAnnotationChecker(),
                SynchronizedAnnotationChecker(),
                LocalFunInlineChecker(),
                ReifiedTypeParameterAnnotationChecker(),
                NativeFunChecker(),
                OverloadsAnnotationChecker(),
                JvmFieldApplicabilityChecker(),
                TypeParameterBoundIsNotArrayChecker()
        ),

        additionalCallCheckers = listOf(
                JavaAnnotationCallChecker(),
                TraitDefaultMethodCallChecker(),
                JavaClassOnCompanionChecker(),
                ProtectedInSuperClassCompanionCallChecker(),
                UnsupportedSyntheticCallableReferenceChecker()
        ),

        additionalTypeCheckers = listOf(
                JavaNullabilityWarningsChecker(),
                RuntimeAssertionsTypeChecker,
                JavaGenericVarianceViolationTypeChecker
        ),

        additionalSymbolUsageValidators = listOf(),

        additionalAnnotationCheckers = listOf(
                RepeatableAnnotationChecker,
                FileClassAnnotationsChecker
        ),

        identifierChecker = JvmSimpleNameBacktickChecker,

        overloadFilter = JvmOverloadFilter
) {

    override fun configure(container: StorageComponentContainer) {
        super.configure(container)

        container.useImpl<ReflectionAPICallChecker>()
        container.useImpl<JavaSyntheticScopes>()
    }
}
