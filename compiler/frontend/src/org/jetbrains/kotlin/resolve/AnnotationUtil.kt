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

package org.jetbrains.kotlin.resolve.annotations

import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.PropertyAccessorDescriptor
import org.jetbrains.kotlin.descriptors.annotations.AnnotationDescriptor
import org.jetbrains.kotlin.descriptors.annotations.AnnotationUseSiteTarget
import org.jetbrains.kotlin.descriptors.annotations.Annotations
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.resolve.constants.ConstantValue
import org.jetbrains.kotlin.resolve.constants.ErrorValue

fun DeclarationDescriptor.hasJvmStaticAnnotation(): Boolean {
    return annotations.findAnnotation(FqName("kotlin.jvm.JvmStatic")) != null
}

fun DeclarationDescriptor.hasJvmSyntheticAnnotation(): Boolean {
    val jvmSyntheticName = FqName("kotlin.jvm.JvmSynthetic")
    return annotations.findAnnotation(jvmSyntheticName) != null ||
           Annotations.findUseSiteTargetedAnnotation(annotations, AnnotationUseSiteTarget.FIELD, jvmSyntheticName) != null
}

fun CallableDescriptor.isPlatformStaticInObjectOrClass(): Boolean =
        isPlatformStaticIn { DescriptorUtils.isNonCompanionObject(it) || DescriptorUtils.isClassOrEnumClass(it) }

fun CallableDescriptor.isPlatformStaticInCompanionObject(): Boolean =
        isPlatformStaticIn { DescriptorUtils.isCompanionObject(it) }

private fun CallableDescriptor.isPlatformStaticIn(predicate: (DeclarationDescriptor) -> Boolean): Boolean =
        when (this) {
            is PropertyAccessorDescriptor -> {
                val propertyDescriptor = correspondingProperty
                predicate(propertyDescriptor.containingDeclaration) &&
                (hasJvmStaticAnnotation() || propertyDescriptor.hasJvmStaticAnnotation())
            }
            else -> predicate(containingDeclaration) && hasJvmStaticAnnotation()
        }

fun AnnotationDescriptor.argumentValue(parameterName: String): Any? {
    val constant: ConstantValue<*>? = allValueArguments.entries
            .singleOrNull { it.key.name.asString() == parameterName }
            ?.value

    if (constant == null || constant is ErrorValue)
        return null

    return constant.value
}