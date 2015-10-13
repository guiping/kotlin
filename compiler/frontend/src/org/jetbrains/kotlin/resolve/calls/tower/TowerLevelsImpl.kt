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

package org.jetbrains.kotlin.resolve.calls.tower

import com.intellij.util.SmartList
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.incremental.components.LookupLocation
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.calls.util.FakeCallableDescriptorForObject
import org.jetbrains.kotlin.resolve.descriptorUtil.hasClassValueDescriptor
import org.jetbrains.kotlin.resolve.scopes.ImportingScope
import org.jetbrains.kotlin.resolve.scopes.ResolutionScope
import org.jetbrains.kotlin.resolve.scopes.receivers.QualifierReceiver
import org.jetbrains.kotlin.resolve.scopes.receivers.ReceiverValue
import org.jetbrains.kotlin.types.ErrorUtils
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.utils.addIfNotNull

internal abstract class AbstractScopeTowerLevel(
        protected val resolveTower: ScopeTower
): ScopeTowerLevel {
    protected val location: LookupLocation get() = resolveTower.location

    protected fun <D : CallableDescriptor> createCandidateDescriptor(
            descriptor: D,
            dispatchReceiver: ReceiverValue?,
            specialError: ResolutionDiagnostic? = null,
            dispatchReceiverSmartCastType: KotlinType? = null
    ): CandidateWithBoundDispatchReceiver<D> {
        val diagnostics = SmartList<ResolutionDiagnostic>()
        diagnostics.addIfNotNull(specialError)

        if (ErrorUtils.isError(descriptor)) diagnostics.add(ErrorDescriptor())
        if (descriptor.isSynthesized) diagnostics.add(SynthesizedDescriptor())
        if (dispatchReceiverSmartCastType != null) diagnostics.add(UsedSmartCastForDispatchReceiver(dispatchReceiverSmartCastType))

        Visibilities.findInvisibleMember(
                dispatchReceiver ?: ReceiverValue.NO_RECEIVER, descriptor,
                resolveTower.lexicalScope.ownerDescriptor
        )?.let { diagnostics.add(VisibilityError(it)) }

        return CandidateWithBoundDispatchReceiverImpl(dispatchReceiver, descriptor, diagnostics)
    }

}

// todo create error for constructors call and for implicit receiver
// todo KT-9538 Unresolved inner class via subclass reference
// todo Future plan: move constructors and fake variables for objects to class member scope.
internal abstract class ConstructorsAndFakeVariableHackLevelScope(resolveTower: ScopeTower) : AbstractScopeTowerLevel(resolveTower) {

    protected fun createConstructors(
            classifier: ClassifierDescriptor?,
            dispatchReceiver: ReceiverValue?,
            dispatchReceiverSmartCastType: KotlinType? = null,
            reportError: (ClassDescriptor) -> ResolutionDiagnostic? = { null }
    ): Collection<CandidateWithBoundDispatchReceiver<FunctionDescriptor>> {
        val classDescriptor = getClassWithConstructors(classifier) ?: return emptyList()

        val specialError = reportError(classDescriptor)
        return classDescriptor.constructors.map {

            val dispatchReceiverHack = if (dispatchReceiver == null && it.dispatchReceiverParameter != null) {
                it.dispatchReceiverParameter?.value // todo this is hack for Scope Level
            }
            else if (dispatchReceiver != null && it.dispatchReceiverParameter == null) { // should be reported error
                null
            }
            else {
                dispatchReceiver
            }

            createCandidateDescriptor(it, dispatchReceiverHack, specialError, dispatchReceiverSmartCastType)
        }
    }

    protected fun createVariableDescriptor(
            classifier: ClassifierDescriptor?,
            dispatchReceiverSmartCastType: KotlinType? = null,
            reportError: (ClassDescriptor) -> ResolutionDiagnostic? = { null }
    ): CandidateWithBoundDispatchReceiver<VariableDescriptor>? {
        val fakeVariable = getFakeDescriptorForObject(classifier) ?: return null
        return createCandidateDescriptor(fakeVariable, null, reportError(fakeVariable.classDescriptor), dispatchReceiverSmartCastType)
    }


    private fun getClassWithConstructors(classifier: ClassifierDescriptor?): ClassDescriptor? {
        if (classifier !is ClassDescriptor || ErrorUtils.isError(classifier)
            // Constructors of singletons shouldn't be callable from the code
            || classifier.kind.isSingleton) {
            return null
        }
        else {
            return classifier
        }
    }

    private fun getFakeDescriptorForObject(classifier: ClassifierDescriptor?): FakeCallableDescriptorForObject? {
        if (classifier !is ClassDescriptor || !classifier.hasClassValueDescriptor) return null // todo

        return FakeCallableDescriptorForObject(classifier)
    }
}

internal class ReceiverScopeTowerLevel(
        resolveTower: ScopeTower,
        val dispatchReceiver: ReceiverValue
): ConstructorsAndFakeVariableHackLevelScope(resolveTower) {
    private val memberScope = dispatchReceiver.type.memberScope

    private fun <D: CallableDescriptor> collectMembers(
            members: ResolutionScope.() -> Collection<D>,
            additionalDescriptors: ResolutionScope.(smartCastType: KotlinType?) -> Collection<CandidateWithBoundDispatchReceiver<D>> // todo
    ): Collection<CandidateWithBoundDispatchReceiver<D>> {
        var result: Collection<CandidateWithBoundDispatchReceiver<D>> = memberScope.members().map {
            createCandidateDescriptor(it, dispatchReceiver)
        } fastPlus memberScope.additionalDescriptors(null)

        val smartCastPossibleTypes = resolveTower.smartCastCache.getPossibleTypes(dispatchReceiver)
        val unstableError = if (resolveTower.smartCastCache.isStableReceiver(dispatchReceiver)) null else UnstableSmartCast()

        for (possibleType in smartCastPossibleTypes) {
            result = result fastPlus possibleType.memberScope.members().map {
                createCandidateDescriptor(it, dispatchReceiver, unstableError, dispatchReceiverSmartCastType = possibleType)
            }

            result = result fastPlus possibleType.memberScope.additionalDescriptors(possibleType).map {
                it.addDiagnostic(unstableError)
            }
        }

        return result
    }

    // todo add static methods & fields with error
    override fun getVariables(name: Name): Collection<CandidateWithBoundDispatchReceiver<VariableDescriptor>> {
        return collectMembers({ getContributedVariables(name, location) }) {
            smartCastType ->
            listOfNotNull(createVariableDescriptor(getContributedClassifier(name, location), smartCastType){ NestedClassViaInstanceReference(it) } )
        }
    }

    override fun getFunctions(name: Name): Collection<CandidateWithBoundDispatchReceiver<FunctionDescriptor>> {
        return collectMembers({ getContributedFunctions(name, location) }) {
            smartCastType ->
            createConstructors(getContributedClassifier(name, location), dispatchReceiver, smartCastType) {
                if (it.isInner) null else NestedClassViaInstanceReference(it)
            }
        }
    }
}

internal class QualifierScopeTowerLevel(resolveTower: ScopeTower, qualifier: QualifierReceiver) : ConstructorsAndFakeVariableHackLevelScope(resolveTower) {
    private val qualifierScope = qualifier.getNestedClassesAndPackageMembersScope()

    override fun getVariables(name: Name): Collection<CandidateWithBoundDispatchReceiver<VariableDescriptor>> {
        val variables = qualifierScope.getContributedVariables(name, location).map {
            createCandidateDescriptor(it, dispatchReceiver = null)
        }
        return variables fastPlus createVariableDescriptor(qualifierScope.getContributedClassifier(name, location))
    }

    override fun getFunctions(name: Name): Collection<CandidateWithBoundDispatchReceiver<FunctionDescriptor>> {
        val functions = qualifierScope.getContributedFunctions(name, location).map {
            createCandidateDescriptor(it, dispatchReceiver = null)
        }
        val constructors = createConstructors(qualifierScope.getContributedClassifier(name, location), dispatchReceiver = null) {
            if (it.isInner) InnerClassViaStaticReference(it) else null
        }
        return functions fastPlus constructors
    }
}

internal open class ScopeScopeTowerLevel(
        resolveTower: ScopeTower,
        private val lexicalScope: ResolutionScope
) : ConstructorsAndFakeVariableHackLevelScope(resolveTower) {

    override fun getVariables(name: Name): Collection<CandidateWithBoundDispatchReceiver<VariableDescriptor>> {
        val variables = lexicalScope.getContributedVariables(name, location).map {
            createCandidateDescriptor(it, dispatchReceiver = null)
        }
        return variables fastPlus createVariableDescriptor(lexicalScope.getContributedClassifier(name, location))
    }

    override fun getFunctions(name: Name): Collection<CandidateWithBoundDispatchReceiver<FunctionDescriptor>> {
        val functions = lexicalScope.getContributedFunctions(name, location).map {
            createCandidateDescriptor(it, dispatchReceiver = null)
        }

        // todo report errors for constructors if there is no match receiver
        return functions fastPlus createConstructors(lexicalScope.getContributedClassifier(name, location), dispatchReceiver = null) {
            if (!it.isInner) return@createConstructors null

            // todo add constructors functions to member class member scope
            // KT-3335 Creating imported super class' inner class fails in codegen
            UnsupportedInnerClassCall("Constructor call for inner class from subclass unsupported")
        }
    }
}

internal class ImportingScopeScopeTowerLevel(
        resolveTower: ScopeTower,
        private val importingScope: ImportingScope,
        private val receiversForSyntheticExtensions: Collection<KotlinType>
): ScopeScopeTowerLevel(resolveTower, importingScope) {

    override fun getVariables(name: Name): Collection<CandidateWithBoundDispatchReceiver<VariableDescriptor>> {
        val synthetic = importingScope.getContributedSyntheticExtensionProperties(receiversForSyntheticExtensions, name, location).map {
            createCandidateDescriptor(it, dispatchReceiver = null)
        }
        return super.getVariables(name) fastPlus synthetic
    }

    override fun getFunctions(name: Name): Collection<CandidateWithBoundDispatchReceiver<FunctionDescriptor>> {
        val synthetic = importingScope.getContributedSyntheticExtensionFunctions(receiversForSyntheticExtensions, name, location).map {
            createCandidateDescriptor(it, dispatchReceiver = null)
        }
        return super.getFunctions(name) fastPlus synthetic
    }
}