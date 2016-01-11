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

package org.jetbrains.kotlin.idea.codeInsight

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.idea.resolve.ResolutionFacade
import org.jetbrains.kotlin.idea.resolve.frontendService
import org.jetbrains.kotlin.idea.util.*
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.parentsWithSelf
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.bindingContextUtil.getDataFlowInfo
import org.jetbrains.kotlin.resolve.calls.smartcasts.SmartCastManager
import org.jetbrains.kotlin.resolve.isHiddenInResolution
import org.jetbrains.kotlin.resolve.scopes.*
import org.jetbrains.kotlin.resolve.scopes.utils.collectDescriptorsFiltered
import org.jetbrains.kotlin.resolve.scopes.utils.memberScopeAsImportingScope
import org.jetbrains.kotlin.synthetic.SyntheticJavaPropertyDescriptor
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.utils.addIfNotNull
import java.util.*

class ReferenceVariantsHelper(
        private val bindingContext: BindingContext,
        private val resolutionFacade: ResolutionFacade,
        private val moduleDescriptor: ModuleDescriptor,
        private val visibilityFilter: (DeclarationDescriptor) -> Boolean
) {
    fun getReferenceVariants(
            expression: KtSimpleNameExpression,
            kindFilter: DescriptorKindFilter,
            nameFilter: (Name) -> Boolean,
            filterOutJavaGettersAndSetters: Boolean = true,
            filterOutShadowed: Boolean = true,
            excludeNonInitializedVariable: Boolean = true,
            useReceiverType: KotlinType? = null
    ): Collection<DeclarationDescriptor>
            = getReferenceVariants(expression, CallTypeAndReceiver.detect(expression),
                                   kindFilter, nameFilter, filterOutJavaGettersAndSetters, filterOutShadowed, excludeNonInitializedVariable, useReceiverType)

    fun getReferenceVariants(
            contextElement: PsiElement,
            callTypeAndReceiver: CallTypeAndReceiver<*, *>,
            kindFilter: DescriptorKindFilter,
            nameFilter: (Name) -> Boolean,
            filterOutJavaGettersAndSetters: Boolean = true,
            filterOutShadowed: Boolean = true,
            excludeNonInitializedVariable: Boolean = true,
            useReceiverType: KotlinType? = null
    ): Collection<DeclarationDescriptor> {
        var variants: Collection<DeclarationDescriptor>
                = getReferenceVariantsNoVisibilityFilter(contextElement, kindFilter, nameFilter, callTypeAndReceiver, useReceiverType)
                .filter { !it.isHiddenInResolution() && visibilityFilter(it) }

        if (filterOutShadowed) {
            ShadowedDeclarationsFilter.create(bindingContext, resolutionFacade, contextElement, callTypeAndReceiver)?.let {
                variants = it.filter(variants)
            }
        }

        if (filterOutJavaGettersAndSetters && kindFilter.kindMask.and(DescriptorKindFilter.FUNCTIONS_MASK) != 0) {
            variants = filterOutJavaGettersAndSetters(variants)
        }

        if (excludeNonInitializedVariable && kindFilter.kindMask.and(DescriptorKindFilter.VARIABLES_MASK) != 0) {
            variants = excludeNonInitializedVariable(variants, contextElement)
        }

        return variants
    }

    fun filterOutJavaGettersAndSetters(variants: Collection<DeclarationDescriptor>): Collection<DeclarationDescriptor> {
        val accessorMethodsToRemove = HashSet<FunctionDescriptor>()
        for (variant in variants) {
            if (variant is SyntheticJavaPropertyDescriptor) {
                accessorMethodsToRemove.add(variant.getMethod.original)
                accessorMethodsToRemove.addIfNotNull(variant.setMethod?.original)
            }
        }

        return variants.filter { it !is FunctionDescriptor || it.original !in accessorMethodsToRemove }
    }

    // filters out variable inside its initializer
    fun excludeNonInitializedVariable(variants: Collection<DeclarationDescriptor>, contextElement: PsiElement): Collection<DeclarationDescriptor> {
        for (element in contextElement.parentsWithSelf) {
            val parent = element.parent
            if (parent is KtVariableDeclaration && element == parent.initializer) {
                val descriptor = bindingContext[BindingContext.DECLARATION_TO_DESCRIPTOR, parent]
                return variants.filter { it != descriptor }
            }
            if (element is KtDeclaration) break // we can use variable inside lambda or anonymous object located in its initializer
        }
        return variants
    }

    private fun getReferenceVariantsNoVisibilityFilter(
            contextElement: PsiElement,
            kindFilter: DescriptorKindFilter,
            nameFilter: (Name) -> Boolean,
            callTypeAndReceiver: CallTypeAndReceiver<*, *>,
            useReceiverType: KotlinType?
    ): Collection<DeclarationDescriptor> {
        val callType = callTypeAndReceiver.callType

        @Suppress("NAME_SHADOWING")
        val kindFilter = kindFilter.intersect(callType.descriptorKindFilter)

        val receiverExpression: KtExpression?
        when (callTypeAndReceiver) {
            is CallTypeAndReceiver.IMPORT_DIRECTIVE -> {
                return getVariantsForImportOrPackageDirective(callTypeAndReceiver.receiver, kindFilter, nameFilter)
            }

            is CallTypeAndReceiver.PACKAGE_DIRECTIVE -> {
                return getVariantsForImportOrPackageDirective(callTypeAndReceiver.receiver, kindFilter, nameFilter)
            }

            is CallTypeAndReceiver.TYPE -> {
                return getVariantsForUserType(callTypeAndReceiver.receiver, contextElement, kindFilter, nameFilter)
            }

            is CallTypeAndReceiver.ANNOTATION -> {
                return getVariantsForUserType(callTypeAndReceiver.receiver, contextElement, kindFilter, nameFilter)
            }

            is CallTypeAndReceiver.CALLABLE_REFERENCE -> {
                val resolutionScope = contextElement.getResolutionScope(bindingContext, resolutionFacade)
                return getVariantsForCallableReference(callTypeAndReceiver.receiver, resolutionScope, kindFilter, nameFilter)
            }

            is CallTypeAndReceiver.DEFAULT -> receiverExpression = null
            is CallTypeAndReceiver.DOT -> receiverExpression = callTypeAndReceiver.receiver
            is CallTypeAndReceiver.SUPER_MEMBERS -> receiverExpression = callTypeAndReceiver.receiver
            is CallTypeAndReceiver.SAFE -> receiverExpression = callTypeAndReceiver.receiver
            is CallTypeAndReceiver.INFIX -> receiverExpression = callTypeAndReceiver.receiver
            is CallTypeAndReceiver.OPERATOR -> return emptyList()
            is CallTypeAndReceiver.UNKNOWN -> return emptyList()
            else -> throw RuntimeException() //TODO: see KT-9394
        }

        val resolutionScope = contextElement.getResolutionScope(bindingContext, resolutionFacade)
        val dataFlowInfo = bindingContext.getDataFlowInfo(contextElement)
        val containingDeclaration = resolutionScope.ownerDescriptor

        val implicitReceiverTypes = resolutionScope.getImplicitReceiversWithInstance().flatMap {
            SmartCastManager.getSmartCastVariantsWithLessSpecificExcluded(it.value, bindingContext, containingDeclaration, dataFlowInfo)
        }.toSet()

        val descriptors = LinkedHashSet<DeclarationDescriptor>()

        if (receiverExpression != null) {
            val qualifier = bindingContext[BindingContext.QUALIFIER, receiverExpression]
            if (qualifier != null) {
                descriptors.addAll(qualifier.scope.getDescriptorsFiltered(kindFilter exclude DescriptorKindExclude.Extensions, nameFilter))
            }

            val explicitReceiverTypes = if (useReceiverType != null) {
                listOf(useReceiverType)
            }
            else {
                callTypeAndReceiver.receiverTypes(bindingContext, contextElement, moduleDescriptor, resolutionFacade, predictableSmartCastsOnly = false)!!
            }

            descriptors.processAll(implicitReceiverTypes, explicitReceiverTypes, resolutionScope, callType, kindFilter, nameFilter)
        }
        else {
            assert(useReceiverType == null) { "'useReceiverType' parameter is not supported for implicit receiver" }

            descriptors.processAll(implicitReceiverTypes, implicitReceiverTypes, resolutionScope, callType, kindFilter, nameFilter)

            // add non-instance members
            descriptors.addAll(resolutionScope.collectDescriptorsFiltered(kindFilter exclude DescriptorKindExclude.Extensions, nameFilter))
        }

        if (callType == CallType.SUPER_MEMBERS) { // we need to unwrap fake overrides in case of "super." because ShadowedDeclarationsFilter does not work correctly
            return descriptors.flatMapTo(LinkedHashSet()) {
                if (it is CallableMemberDescriptor && it.kind == CallableMemberDescriptor.Kind.FAKE_OVERRIDE) it.overriddenDescriptors else listOf(it)
            }
        }

        return descriptors
    }

    private fun getVariantsForUserType(
            receiverExpression: KtExpression?,
            contextElement: PsiElement,
            kindFilter: DescriptorKindFilter,
            nameFilter: (Name) -> Boolean
    ): Collection<DeclarationDescriptor> {
        if (receiverExpression != null) {
            val qualifier = bindingContext[BindingContext.QUALIFIER, receiverExpression] ?: return emptyList()
            return qualifier.scope.getDescriptorsFiltered(kindFilter, nameFilter)
        }
        else {
            val scope = contextElement.getResolutionScope(bindingContext, resolutionFacade)
            return scope.collectDescriptorsFiltered(kindFilter, nameFilter)
        }
    }

    private fun getVariantsForCallableReference(
            qualifierTypeRef: KtTypeReference?,
            resolutionScope: LexicalScope,
            kindFilter: DescriptorKindFilter,
            nameFilter: (Name) -> Boolean
    ): Collection<DeclarationDescriptor> {
        val descriptors = LinkedHashSet<DeclarationDescriptor>()
        if (qualifierTypeRef != null) {
            val type = bindingContext[BindingContext.TYPE, qualifierTypeRef] ?: return emptyList()

            descriptors.addNonExtensionMembers(listOf(type), kindFilter, nameFilter, constructorFilter = { true })

            descriptors.addScopeAndSyntheticExtensions(resolutionScope, listOf(type), CallType.CALLABLE_REFERENCE, kindFilter, nameFilter)
        }
        else {
            // process non-instance members and class constructors
            descriptors.addNonExtensionCallablesAndConstructors(resolutionScope, kindFilter, nameFilter, constructorFilter = { !it.isInner })
        }
        return descriptors
    }

    private fun getVariantsForImportOrPackageDirective(
            receiverExpression: KtExpression?,
            kindFilter: DescriptorKindFilter,
            nameFilter: (Name) -> Boolean
    ): Collection<DeclarationDescriptor> {
        if (receiverExpression != null) {
            val qualifier = bindingContext[BindingContext.QUALIFIER, receiverExpression] ?: return emptyList()
            return qualifier.scope.getDescriptorsFiltered(kindFilter, nameFilter)
        }
        else {
            val rootPackage = resolutionFacade.moduleDescriptor.getPackage(FqName.ROOT)
            return rootPackage.memberScope.getDescriptorsFiltered(kindFilter, nameFilter)
        }
    }

    private fun MutableSet<DeclarationDescriptor>.processAll(
            implicitReceiverTypes: Collection<KotlinType>,
            receiverTypes: Collection<KotlinType>,
            resolutionScope: LexicalScope,
            callType: CallType<*>,
            kindFilter: DescriptorKindFilter,
            nameFilter: (Name) -> Boolean
    ) {
        addNonExtensionMembers(receiverTypes, kindFilter, nameFilter, constructorFilter = { it.isInner })
        addMemberExtensions(implicitReceiverTypes, receiverTypes, callType, kindFilter, nameFilter)
        addScopeAndSyntheticExtensions(resolutionScope, receiverTypes, callType, kindFilter, nameFilter)
    }

    private fun MutableSet<DeclarationDescriptor>.addMemberExtensions(
            dispatchReceiverTypes: Collection<KotlinType>,
            extensionReceiverTypes: Collection<KotlinType>,
            callType: CallType<*>,
            kindFilter: DescriptorKindFilter,
            nameFilter: (Name) -> Boolean
    ) {
        val memberFilter = kindFilter exclude DescriptorKindExclude.NonExtensions
        for (dispatchReceiverType in dispatchReceiverTypes) {
            for (member in dispatchReceiverType.memberScope.getDescriptorsFiltered(memberFilter, nameFilter)) {
                addAll((member as CallableDescriptor).substituteExtensionIfCallable(extensionReceiverTypes, callType))
            }
        }
    }

    private fun MutableSet<DeclarationDescriptor>.addNonExtensionMembers(
            receiverTypes: Collection<KotlinType>,
            kindFilter: DescriptorKindFilter,
            nameFilter: (Name) -> Boolean,
            constructorFilter: (ClassDescriptor) -> Boolean
    ) {
        for (receiverType in receiverTypes) {
            addNonExtensionCallablesAndConstructors(receiverType.memberScope.memberScopeAsImportingScope(), kindFilter, nameFilter, constructorFilter)
        }
    }

    private fun MutableSet<DeclarationDescriptor>.addNonExtensionCallablesAndConstructors(
            scope: HierarchicalScope,
            kindFilter: DescriptorKindFilter,
            nameFilter: (Name) -> Boolean,
            constructorFilter: (ClassDescriptor) -> Boolean
    ) {
        var filterToUse = DescriptorKindFilter(kindFilter.kindMask and DescriptorKindFilter.CALLABLES.kindMask).exclude(DescriptorKindExclude.Extensions)

        // should process classes if we need constructors
        if (filterToUse.acceptsKinds(DescriptorKindFilter.FUNCTIONS_MASK)) {
            filterToUse = filterToUse.withKinds(DescriptorKindFilter.NON_SINGLETON_CLASSIFIERS_MASK)
        }

        for (descriptor in scope.collectDescriptorsFiltered(filterToUse, nameFilter)) {
            if (descriptor is ClassDescriptor) {
                if (descriptor.modality == Modality.ABSTRACT || descriptor.modality == Modality.SEALED) continue
                if (!constructorFilter(descriptor)) continue
                descriptor.constructors.filterTo(this) { kindFilter.accepts(it) }
            }
            else if (kindFilter.accepts(descriptor)) {
                this.add(descriptor)
            }
        }
    }

    private fun MutableSet<DeclarationDescriptor>.addScopeAndSyntheticExtensions(
            scope: LexicalScope,
            receiverTypes: Collection<KotlinType>,
            callType: CallType<*>,
            kindFilter: DescriptorKindFilter,
            nameFilter: (Name) -> Boolean
    ) {
        if (kindFilter.excludes.contains(DescriptorKindExclude.Extensions)) return
        if (receiverTypes.isEmpty()) return

        fun process(extension: CallableDescriptor) {
            if (kindFilter.accepts(extension) && nameFilter(extension.name)) {
                addAll(extension.substituteExtensionIfCallable(receiverTypes, callType))
            }
        }

        for (descriptor in scope.collectDescriptorsFiltered(kindFilter exclude DescriptorKindExclude.NonExtensions, nameFilter)) {
            // todo: sometimes resolution scope here is LazyJavaClassMemberScope. see ea.jetbrains.com/browser/ea_problems/72572
            process(descriptor as CallableDescriptor)
        }

        val syntheticScopes = resolutionFacade.getFrontendService(SyntheticScopes::class.java)
        if (kindFilter.acceptsKinds(DescriptorKindFilter.VARIABLES_MASK)) {
            for (extension in syntheticScopes.collectSyntheticExtensionProperties(receiverTypes)) {
                process(extension)
            }
        }

        if (kindFilter.acceptsKinds(DescriptorKindFilter.FUNCTIONS_MASK)) {
            for (extension in syntheticScopes.collectSyntheticExtensionFunctions(receiverTypes)) {
                process(extension)
            }
        }
    }
}
