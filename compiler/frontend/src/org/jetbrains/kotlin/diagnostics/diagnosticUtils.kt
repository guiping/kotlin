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

package org.jetbrains.kotlin.diagnostics

import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.BindingContextUtils
import org.jetbrains.kotlin.resolve.BindingTrace
import org.jetbrains.kotlin.resolve.calls.callUtil.getParentCall
import org.jetbrains.kotlin.resolve.calls.callUtil.getResolvedCall
import org.jetbrains.kotlin.resolve.calls.model.ResolvedCall
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.expressions.OperatorConventions
import org.jetbrains.kotlin.types.typeUtil.isNothing
import org.jetbrains.kotlin.types.typesApproximation.containsApproximatedCapturedTypeProjection

fun BindingTrace.reportTypeMismatch(expression: KtExpression, expectedType: KotlinType, expressionType: KotlinType) {
    if (reportTypeMismatchDueToTypeProjection(expression, expectedType, expressionType)) return

    report(Errors.TYPE_MISMATCH.on(expression, expectedType, expressionType))
}

fun BindingTrace.reportTypeMismatchDueToTypeProjection(
        expression: KtElement,
        expectedType: KotlinType,
        expressionType: KotlinType?
): Boolean {
    if (!expectedType.containsApproximatedCapturedTypeProjection()) return false

    if (expectedType.isNothing()) {
        expression.leftOperandIfParentIsAssignment(bindingContext)?.let {
            p -> report(Errors.SETTER_PROJECTED_OUT.on(p.first, p.second))
            return@reportTypeMismatchDueToTypeProjection true
        }
    }

    return expression.getParentCall(bindingContext, strict = true)?.let {
        call ->

        val resolvedCall: ResolvedCall<out CallableDescriptor> = call.getResolvedCall(bindingContext) ?: return@let null
        val receiverType = resolvedCall.dispatchReceiver?.type ?: return@let null

        val callableDescriptor = resolvedCall.resultingDescriptor.original
        if (expectedType.isNothing()) {
            report(Errors.MEMBER_PROJECTED_OUT.on(call.calleeExpression ?: call.callElement, callableDescriptor, receiverType))
        }
        else {
            expressionType ?: return@let null
            report(
                    Errors.TYPE_MISMATCH_DUE_TO_TYPE_PROJECTIONS.on(
                            expression, expectedType, expressionType, receiverType, callableDescriptor))
        }

        true
    } ?: false // Report common type mismatch error if something went wrong
}

private fun KtElement.leftOperandIfParentIsAssignment(bindingContext: BindingContext): Pair<KtExpression, PropertyDescriptor>? {
    val binaryExpression = this as? KtBinaryExpression ?: parent as? KtBinaryExpression ?: return null

    val operationType = binaryExpression.operationReference.getReferencedNameElementType()
    if (operationType != KtTokens.EQ && !OperatorConventions.ASSIGNMENT_OPERATIONS.containsKey(operationType)) return null

    val propertyDescriptor = BindingContextUtils.extractVariableFromResolvedCall(bindingContext, binaryExpression.left) as? PropertyDescriptor
                             ?: return null

    return Pair(binaryExpression.left!!, propertyDescriptor)
}
