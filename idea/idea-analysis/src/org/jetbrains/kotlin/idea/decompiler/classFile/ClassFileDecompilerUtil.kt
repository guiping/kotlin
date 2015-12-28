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

package org.jetbrains.kotlin.idea.decompiler.classFile

import com.intellij.ide.highlighter.JavaClassFileType
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.ClassFileViewProvider
import org.jetbrains.kotlin.idea.caches.JarUserDataManager
import org.jetbrains.kotlin.load.kotlin.KotlinBinaryClassCache
import org.jetbrains.kotlin.load.kotlin.KotlinJvmBinaryClass
import org.jetbrains.kotlin.load.kotlin.header.KotlinClassHeader
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name

/**
 * Checks if this file is a compiled Kotlin class file (not necessarily ABI-compatible with the current plugin)
 */
fun isKotlinJvmCompiledFile(file: VirtualFile): Boolean {
    if (file.extension != JavaClassFileType.INSTANCE!!.defaultExtension) {
        return false
    }

    if (HasCompiledKotlinInJar.isInNoKotlinJar(file)) {
        return false
    }

    val header = KotlinBinaryClassCache.getKotlinBinaryClass(file)?.classHeader
    return header != null
}

/**
 * Checks if this file is a compiled Kotlin class file ABI-compatible with the current plugin
 */
fun isKotlinWithCompatibleAbiVersion(file: VirtualFile): Boolean {
    if (!isKotlinJvmCompiledFile(file)) return false

    val kotlinClass = KotlinBinaryClassCache.getKotlinBinaryClass(file)
    return kotlinClass != null && kotlinClass.classHeader.version.isCompatible()
}

/**
 * Checks if this file is a compiled "internal" Kotlin class, i.e. a Kotlin class (not necessarily ABI-compatible with the current plugin)
 * which should NOT be decompiled (and, as a result, shown under the library in the Project view, be searchable via Find class, etc.)
 */
fun isKotlinInternalCompiledFile(file: VirtualFile): Boolean {
    if (!isKotlinJvmCompiledFile(file)) {
        return false
    }

    if (ClassFileViewProvider.isInnerClass(file)) {
        return true
    }
    val header = KotlinBinaryClassCache.getKotlinBinaryClass(file)?.classHeader ?: return false

    return header.kind == KotlinClassHeader.Kind.SYNTHETIC_CLASS ||
           header.kind == KotlinClassHeader.Kind.MULTIFILE_CLASS_PART ||
           header.isLocalClass
}

object HasCompiledKotlinInJar : JarUserDataManager.JarBooleanPropertyCounter(HasCompiledKotlinInJar::class.simpleName!!) {
    override fun hasProperty(file: VirtualFile) = isKotlinJvmCompiledFile(file)

    fun isInNoKotlinJar(file: VirtualFile): Boolean =
            JarUserDataManager.hasFileWithProperty(HasCompiledKotlinInJar, file) == false
}

fun findMultifileClassParts(file: VirtualFile, multifileClass: KotlinJvmBinaryClass): List<KotlinJvmBinaryClass> {
    val packageFqName = multifileClass.classId.packageFqName
    val partsFinder = DirectoryBasedClassFinder(file.parent!!, packageFqName)
    val partNames = multifileClass.classHeader.filePartClassNames ?: return emptyList()
    return partNames.mapNotNull {
        partsFinder.findKotlinClass(ClassId(packageFqName, Name.identifier(it.substringAfterLast('/'))))
    }
}