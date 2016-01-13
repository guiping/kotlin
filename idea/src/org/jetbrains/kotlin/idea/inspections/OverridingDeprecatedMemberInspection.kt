/*
 * Copyright 2010-2016 JetBrains s.r.o.
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

package org.jetbrains.kotlin.idea.inspections

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import org.jetbrains.kotlin.descriptors.CallableMemberDescriptor
import org.jetbrains.kotlin.idea.caches.resolve.resolveToDescriptor
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.KtVisitorVoid
import org.jetbrains.kotlin.resolve.getDeprecation
import org.jetbrains.kotlin.resolve.isDeprecatedByOverridden

class OverridingDeprecatedMemberInspection : AbstractKotlinInspection() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : KtVisitorVoid() {
            override fun visitNamedDeclaration(declaration: KtNamedDeclaration) {
                val memberDescriptor = declaration.resolveToDescriptor() as? CallableMemberDescriptor ?: return

                val deprecation = memberDescriptor.getDeprecation()
                if (deprecation.isDeprecatedByOverridden()) {
                    val problem = holder.manager.createProblemDescriptor(
                            declaration.nameIdentifier ?: declaration,
                            "${declaration.name} overrides deprecated member",
                            isOnTheFly,
                            emptyArray<LocalQuickFix>(),
                            ProblemHighlightType.LIKE_DEPRECATED
                    )
                    holder.registerProblem(problem)
                }
            }

            override fun visitPropertyAccessor(accessor: KtPropertyAccessor) {
                val accessorDescriptor = accessor.resolveToDescriptor() as? CallableMemberDescriptor ?: return
                val deprecation = accessorDescriptor.getDeprecation()
                if (deprecation.isDeprecatedByOverridden()) {
                    val problem = holder.manager.createProblemDescriptor(
                            accessor.namePlaceholder,
                            "${if (accessor.isGetter) "getter" else "setter"} for ${accessor.property.name ?: ""} overrides deprecated accessor",
                            isOnTheFly,
                            emptyArray<LocalQuickFix>(),
                            ProblemHighlightType.LIKE_DEPRECATED
                    )
                    holder.registerProblem(problem)
                }
            }
        }
    }
}