// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.uast.kotlin

import com.intellij.lang.Language
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.registry.Registry
import com.intellij.psi.*
import com.intellij.psi.impl.source.tree.LeafPsiElement
import org.jetbrains.annotations.TestOnly
import org.jetbrains.kotlin.asJava.LightClassUtil
import org.jetbrains.kotlin.asJava.classes.KtLightClass
import org.jetbrains.kotlin.asJava.classes.KtLightClassForFacade
import org.jetbrains.kotlin.asJava.elements.*
import org.jetbrains.kotlin.asJava.findFacadeClass
import org.jetbrains.kotlin.asJava.toLightClass
import org.jetbrains.kotlin.codegen.state.KotlinTypeMapper
import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.descriptors.ConstructorDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.kdoc.psi.impl.KDocLink
import org.jetbrains.kotlin.kdoc.psi.impl.KDocName
import org.jetbrains.kotlin.kdoc.psi.impl.KDocSection
import org.jetbrains.kotlin.kdoc.psi.impl.KDocTag
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.calls.callUtil.getResolvedCall
import org.jetbrains.kotlin.utils.addToStdlib.safeAs
import org.jetbrains.uast.*
import org.jetbrains.uast.analysis.UastAnalysisPlugin
import org.jetbrains.uast.expressions.UInjectionHost
import org.jetbrains.uast.kotlin.KotlinConverter.convertDeclaration
import org.jetbrains.uast.kotlin.KotlinConverter.convertDeclarationOrElement
import org.jetbrains.uast.kotlin.declarations.KotlinUIdentifier
import org.jetbrains.uast.kotlin.declarations.KotlinUMethod
import org.jetbrains.uast.kotlin.declarations.KotlinUMethodWithFakeLightDelegate
import org.jetbrains.uast.kotlin.expressions.*
import org.jetbrains.uast.kotlin.psi.*
import org.jetbrains.uast.util.ClassSet
import org.jetbrains.uast.util.ClassSetsWrapper

interface KotlinUastResolveProviderService {
    fun getBindingContext(element: KtElement): BindingContext
    fun getTypeMapper(element: KtElement): KotlinTypeMapper?
    fun getLanguageVersionSettings(element: KtElement): LanguageVersionSettings
    fun isJvmElement(psiElement: PsiElement): Boolean
    fun getReferenceVariants(ktElement: KtElement, nameHint: String): Sequence<DeclarationDescriptor>
}

var PsiElement.destructuringDeclarationInitializer: Boolean? by UserDataProperty(Key.create("kotlin.uast.destructuringDeclarationInitializer"))

class KotlinUastLanguagePlugin : UastLanguagePlugin {
    override val priority = 10

    override val language: Language
        get() = KotlinLanguage.INSTANCE

    override fun isFileSupported(fileName: String): Boolean {
        return fileName.endsWith(".kt", false) || fileName.endsWith(".kts", false)
    }

    private val PsiElement.isJvmElement: Boolean
        get() {
            val resolveProvider: KotlinUastResolveProviderService = project.getService(KotlinUastResolveProviderService::class.java)!!
            return resolveProvider.isJvmElement(this)
        }

    override fun convertElement(element: PsiElement, parent: UElement?, requiredType: Class<out UElement>?): UElement? {
        val requiredTypes = elementTypes(requiredType)
        return if (!canConvert(element, requiredTypes) || !element.isJvmElement) null
        else convertDeclarationOrElement(element, parent, requiredTypes)
    }

    override fun convertElementWithParent(element: PsiElement, requiredType: Class<out UElement>?): UElement? {
        val requiredTypes = elementTypes(requiredType)
        return when {
            !canConvert(element, requiredTypes) || !element.isJvmElement -> null
            element is PsiFile || element is KtLightClassForFacade -> convertDeclaration(element, null, requiredTypes)
            else -> convertDeclarationOrElement(element, null, requiredTypes)
        }
    }

    override fun getMethodCallExpression(
            element: PsiElement,
            containingClassFqName: String?,
            methodName: String
    ): UastLanguagePlugin.ResolvedMethod? {
        if (element !is KtCallExpression) return null
        val resolvedCall = element.getResolvedCall(element.analyze()) ?: return null
        val resultingDescriptor = resolvedCall.resultingDescriptor
        if (resultingDescriptor !is FunctionDescriptor || resultingDescriptor.name.asString() != methodName) return null

        val parent = element.parent
        val parentUElement = convertElementWithParent(parent, null) ?: return null

        val uExpression = KotlinUFunctionCallExpression(element, parentUElement, resolvedCall)
        val method = uExpression.resolve() ?: return null
        if (method.name != methodName) return null
        return UastLanguagePlugin.ResolvedMethod(uExpression, method)
    }

    override fun getConstructorCallExpression(
            element: PsiElement,
            fqName: String
    ): UastLanguagePlugin.ResolvedConstructor? {
        if (element !is KtCallExpression) return null
        val resolvedCall = element.getResolvedCall(element.analyze()) ?: return null
        val resultingDescriptor = resolvedCall.resultingDescriptor
        if (resultingDescriptor !is ConstructorDescriptor
                || resultingDescriptor.returnType.constructor.declarationDescriptor?.name?.asString() != fqName) {
            return null
        }

        val parent = KotlinConverter.unwrapElements(element.parent) ?: return null
        val parentUElement = convertElementWithParent(parent, null) ?: return null

        val uExpression = KotlinUFunctionCallExpression(element, parentUElement, resolvedCall)
        val method = uExpression.resolve() ?: return null
        val containingClass = method.containingClass ?: return null
        return UastLanguagePlugin.ResolvedConstructor(uExpression, method, containingClass)
    }

    override fun isExpressionValueUsed(element: UExpression): Boolean {
        return when (element) {
            is KotlinUSimpleReferenceExpression.KotlinAccessorCallExpression -> element.setterValue != null
            is KotlinAbstractUExpression -> {
                val ktElement = element.sourcePsi as? KtElement ?: return false
                ktElement.analyze()[BindingContext.USED_AS_EXPRESSION, ktElement] ?: false
            }
            else -> false
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : UElement> convertElement(element: PsiElement, parent: UElement?, expectedTypes: Array<out Class<out T>>): T? {
        val nonEmptyExpectedTypes = expectedTypes.nonEmptyOr(DEFAULT_TYPES_LIST)
        return if (!canConvert(element, nonEmptyExpectedTypes) || !element.isJvmElement) null
        else convertDeclarationOrElement(element, parent, nonEmptyExpectedTypes) as? T
    }

    override fun <T : UElement> convertElementWithParent(element: PsiElement, requiredTypes: Array<out Class<out T>>): T? {
        return convertElement(element, null, requiredTypes)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : UElement> convertToAlternatives(element: PsiElement, requiredTypes: Array<out Class<out T>>): Sequence<T> =
        if (!element.isJvmElement) emptySequence() else when {
            element is KtFile -> KotlinConverter.convertKtFile(element, null, requiredTypes) as Sequence<T>
            (element is KtProperty && !element.isLocal) ->
                KotlinConverter.convertNonLocalProperty(element, null, requiredTypes) as Sequence<T>
            element is KtParameter -> KotlinConverter.convertParameter(element, null, requiredTypes) as Sequence<T>
            element is KtClassOrObject -> KotlinConverter.convertClassOrObject(element, null, requiredTypes) as Sequence<T>
            element is UastFakeLightPrimaryConstructor ->
                KotlinConverter.convertFakeLightConstructorAlternatices(element, null, requiredTypes) as Sequence<T>
            else -> sequenceOf(convertElementWithParent(element, requiredTypes.nonEmptyOr(DEFAULT_TYPES_LIST)) as? T).filterNotNull()
        }

    override fun getPossiblePsiSourceTypes(vararg uastTypes: Class<out UElement>): ClassSet<PsiElement> =
        when (uastTypes.size) {
            0 -> getPossibleSourceTypes(UElement::class.java)
            1 -> getPossibleSourceTypes(uastTypes.single())
            else -> ClassSetsWrapper<PsiElement>(Array(uastTypes.size) { getPossibleSourceTypes(uastTypes[it]) })
        }

    override val analysisPlugin: UastAnalysisPlugin?
        get() = UastAnalysisPlugin.byLanguage(KotlinLanguage.INSTANCE)
}

internal inline fun <reified ActualT : UElement> Class<*>?.el(f: () -> UElement?): UElement? {
    return if (this == null || isAssignableFrom(ActualT::class.java)) f() else null
}

internal inline fun <reified ActualT : UElement> Array<out Class<out UElement>>.el(f: () -> UElement?): UElement? {
    return if (isAssignableFrom(ActualT::class.java)) f() else null
}

internal inline fun <reified ActualT : UElement> Array<out Class<out UElement>>.expr(f: () -> UExpression?): UExpression? {
    return if (isAssignableFrom(ActualT::class.java)) f() else null
}

internal fun Array<out Class<out UElement>>.isAssignableFrom(cls: Class<*>) = any { it.isAssignableFrom(cls) }


@TestOnly
object KotlinConverter {
    internal tailrec fun unwrapElements(element: PsiElement?): PsiElement? = when (element) {
        is KtValueArgumentList -> unwrapElements(element.parent)
        is KtValueArgument -> unwrapElements(element.parent)
        is KtDeclarationModifierList -> unwrapElements(element.parent)
        is KtContainerNode -> unwrapElements(element.parent)
        is KtSimpleNameStringTemplateEntry -> unwrapElements(element.parent)
        is KtLightParameterList -> unwrapElements(element.parent)
        is KtTypeElement -> unwrapElements(element.parent)
        is KtSuperTypeList -> unwrapElements(element.parent)
        is KtFinallySection -> unwrapElements(element.parent)
        is KtAnnotatedExpression -> unwrapElements(element.parent)
        is KtWhenConditionWithExpression -> unwrapElements(element.parent)
        is KDocLink -> unwrapElements(element.parent)
        is KDocSection -> unwrapElements(element.parent)
        is KDocTag -> unwrapElements(element.parent)
        else -> element
    }

    private val identifiersTokens = setOf(
        KtTokens.IDENTIFIER, KtTokens.CONSTRUCTOR_KEYWORD, KtTokens.OBJECT_KEYWORD,
        KtTokens.THIS_KEYWORD, KtTokens.SUPER_KEYWORD,
        KtTokens.GET_KEYWORD, KtTokens.SET_KEYWORD
    )

    internal fun convertPsiElement(element: PsiElement?,
                                   givenParent: UElement?,
                                   expectedTypes: Array<out Class<out UElement>>
    ): UElement? {
        fun <P : PsiElement> build(ctor: (P, UElement?) -> UElement): () -> UElement? {
            return {
                @Suppress("UNCHECKED_CAST")
                ctor(element as P, givenParent)
            }
        }

        return with (expectedTypes) { when (element) {
            is KtParameterList -> el<UDeclarationsExpression> {
                val declarationsExpression = KotlinUDeclarationsExpression(givenParent)
                declarationsExpression.apply {
                    declarations = element.parameters.mapIndexed { i, p ->
                        KotlinUParameter(UastKotlinPsiParameter.create(p, element, declarationsExpression, i), p, this)
                    }
                }
            }
            is KtClassBody -> el<UExpressionList>(build(KotlinUExpressionList.Companion::createClassBody))
            is KtCatchClause -> el<UCatchClause>(build(::KotlinUCatchClause))
            is KtVariableDeclaration ->
                if (element is KtProperty && !element.isLocal) {
                    convertNonLocalProperty(element, givenParent, this).firstOrNull()
                }
                else {
                    el<UVariable> { convertVariablesDeclaration(element, givenParent).declarations.singleOrNull() }
                        ?: expr<UDeclarationsExpression> { KotlinConverter.convertExpression(element, givenParent, expectedTypes) }
                }

            is KtExpression -> KotlinConverter.convertExpression(element, givenParent, expectedTypes)
            is KtLambdaArgument -> element.getLambdaExpression()?.let { KotlinConverter.convertExpression(it, givenParent, expectedTypes) }
            is KtLightElementBase -> {
                val expression = element.kotlinOrigin
                when (expression) {
                    is KtExpression -> KotlinConverter.convertExpression(expression, givenParent, expectedTypes)
                    else -> el<UExpression> { UastEmptyExpression(givenParent) }
                }
            }
            is KtLiteralStringTemplateEntry, is KtEscapeStringTemplateEntry -> el<ULiteralExpression>(build(::KotlinStringULiteralExpression))
            is KtStringTemplateEntry -> element.expression?.let { convertExpression(it, givenParent, expectedTypes) } ?: expr<UExpression> { UastEmptyExpression(givenParent) }
            is KtWhenEntry -> el<USwitchClauseExpressionWithBody>(build(::KotlinUSwitchEntry))
            is KtWhenCondition -> convertWhenCondition(element, givenParent, expectedTypes)
            is KtTypeReference ->
                expectedTypes.accommodate(
                    alternative { LazyKotlinUTypeReferenceExpression(element, givenParent) },
                    alternative { convertReceiverParameter(element) }
                ).firstOrNull()
            is KtConstructorDelegationCall ->
                el<UCallExpression> { KotlinUFunctionCallExpression(element, givenParent) }
            is KtSuperTypeCallEntry ->
                el<UExpression> {
                    (element.getParentOfType<KtClassOrObject>(true)?.parent as? KtObjectLiteralExpression)
                        ?.toUElementOfType<UExpression>()
                        ?: KotlinUFunctionCallExpression(element, givenParent)
                }
            is KtImportDirective -> el<UImportStatement>(build(::KotlinUImportStatement))
            is PsiComment -> el<UComment>(build(::UComment))
            is KDocName -> {
                if (element.getQualifier() == null)
                    el<USimpleNameReferenceExpression> {
                        element.lastChild?.let { psiIdentifier ->
                            KotlinStringUSimpleReferenceExpression(psiIdentifier.text, givenParent, element, element)
                        }
                    }
                else el<UQualifiedReferenceExpression>(build(::KotlinDocUQualifiedReferenceExpression))
            }
            else -> {
                if (element is LeafPsiElement) {
                    if (element.elementType in identifiersTokens)
                        if (element.elementType != KtTokens.OBJECT_KEYWORD || element.getParentOfType<KtObjectDeclaration>(false)?.nameIdentifier == null)
                            el<UIdentifier>(build(::KotlinUIdentifier))
                        else null
                    else if (element.elementType in KtTokens.OPERATIONS && element.parent is KtOperationReferenceExpression)
                        el<UIdentifier>(build(::KotlinUIdentifier))
                    else if (element.elementType == KtTokens.LBRACKET && element.parent is KtCollectionLiteralExpression)
                        el<UIdentifier> {
                            UIdentifier(
                                element,
                                KotlinUCollectionLiteralExpression(
                                    element.parent as KtCollectionLiteralExpression,
                                    null
                                )
                            )
                        }
                    else null
                } else null
            }
        }}
    }

    internal fun convertReceiverParameter(receiver: KtTypeReference): UParameter? {
        val call = (receiver.parent as? KtCallableDeclaration) ?: return null
        if (call.receiverTypeReference != receiver) return null
        return call.toUElementOfType<UMethod>()?.uastParameters?.firstOrNull()
    }

    internal fun convertEntry(entry: KtStringTemplateEntry,
                              givenParent: UElement?,
                              requiredType: Array<out Class<out UElement>>
    ): UExpression? {
        return with(requiredType) {
            if (entry is KtStringTemplateEntryWithExpression) {
                expr<UExpression> {
                    KotlinConverter.convertOrEmpty(entry.expression, givenParent)
                }
            }
            else {
                expr<ULiteralExpression> {
                    if (entry is KtEscapeStringTemplateEntry)
                        KotlinStringULiteralExpression(entry, givenParent, entry.unescapedValue)
                    else
                        KotlinStringULiteralExpression(entry, givenParent)
                }
            }
        }
    }

    var forceUInjectionHost = Registry.`is`("kotlin.uast.force.uinjectionhost", false)
        @TestOnly
        set(value) {
            field = value
        }

    internal fun convertExpression(expression: KtExpression,
                                   givenParent: UElement?,
                                   requiredType: Array<out Class<out UElement>>
    ): UExpression? {
        fun <P : PsiElement> build(ctor: (P, UElement?) -> UExpression): () -> UExpression? {
            return {
                @Suppress("UNCHECKED_CAST")
                ctor(expression as P, givenParent)
            }
        }

        return with (requiredType) { when (expression) {
            is KtVariableDeclaration -> expr<UDeclarationsExpression>(build(::convertVariablesDeclaration))

            is KtStringTemplateExpression -> {
                when {
                    forceUInjectionHost || requiredType.contains(UInjectionHost::class.java) ->
                        expr<UInjectionHost> { KotlinStringTemplateUPolyadicExpression(expression, givenParent) }
                    expression.entries.isEmpty() -> {
                        expr<ULiteralExpression> { KotlinStringULiteralExpression(expression, givenParent, "") }
                    }

                    expression.entries.size == 1 -> convertEntry(expression.entries[0], givenParent, requiredType)

                    else ->
                        expr<KotlinStringTemplateUPolyadicExpression> { KotlinStringTemplateUPolyadicExpression(expression, givenParent) }
                }
            }
            is KtDestructuringDeclaration -> expr<UDeclarationsExpression> {
                val declarationsExpression = KotlinUDestructuringDeclarationExpression(givenParent, expression)
                declarationsExpression.apply {
                    val tempAssignment = KotlinULocalVariable(UastKotlinPsiVariable.create(expression, declarationsExpression), expression, declarationsExpression)
                    val destructuringAssignments = expression.entries.mapIndexed { i, entry ->
                        val psiFactory = KtPsiFactory(expression.project)
                        val initializer = psiFactory.createAnalyzableExpression("${tempAssignment.name}.component${i + 1}()",
                                                                                expression.containingFile)
                        initializer.destructuringDeclarationInitializer = true
                        KotlinULocalVariable(UastKotlinPsiVariable.create(entry, tempAssignment.javaPsi, declarationsExpression, initializer), entry, declarationsExpression)
                    }
                    declarations = listOf(tempAssignment) + destructuringAssignments
                }
            }
            is KtLabeledExpression -> expr<ULabeledExpression>(build(::KotlinULabeledExpression))
            is KtClassLiteralExpression -> expr<UClassLiteralExpression>(build(::KotlinUClassLiteralExpression))
            is KtObjectLiteralExpression -> expr<UObjectLiteralExpression>(build(::KotlinUObjectLiteralExpression))
            is KtDotQualifiedExpression -> expr<UQualifiedReferenceExpression>(build(::KotlinUQualifiedReferenceExpression))
            is KtSafeQualifiedExpression -> expr<UQualifiedReferenceExpression>(build(::KotlinUSafeQualifiedExpression))
            is KtSimpleNameExpression -> expr<USimpleNameReferenceExpression>(build(::KotlinUSimpleReferenceExpression))
            is KtCallExpression -> expr<UCallExpression>(build(::KotlinUFunctionCallExpression))
            is KtCollectionLiteralExpression -> expr<UCallExpression>(build(::KotlinUCollectionLiteralExpression))
            is KtBinaryExpression -> {
                if (expression.operationToken == KtTokens.ELVIS) {
                    expr<UExpressionList>(build(::createElvisExpression))
                }
                else expr<UBinaryExpression>(build(::KotlinUBinaryExpression))
            }
            is KtParenthesizedExpression -> expr<UParenthesizedExpression>(build(::KotlinUParenthesizedExpression))
            is KtPrefixExpression -> expr<UPrefixExpression>(build(::KotlinUPrefixExpression))
            is KtPostfixExpression -> expr<UPostfixExpression>(build(::KotlinUPostfixExpression))
            is KtThisExpression -> expr<UThisExpression>(build(::KotlinUThisExpression))
            is KtSuperExpression -> expr<USuperExpression>(build(::KotlinUSuperExpression))
            is KtCallableReferenceExpression -> expr<UCallableReferenceExpression>(build(::KotlinUCallableReferenceExpression))
            is KtIsExpression -> expr<UBinaryExpressionWithType>(build(::KotlinUTypeCheckExpression))
            is KtIfExpression -> expr<UIfExpression>(build(::KotlinUIfExpression))
            is KtWhileExpression -> expr<UWhileExpression>(build(::KotlinUWhileExpression))
            is KtDoWhileExpression -> expr<UDoWhileExpression>(build(::KotlinUDoWhileExpression))
            is KtForExpression -> expr<UForEachExpression>(build(::KotlinUForEachExpression))
            is KtWhenExpression -> expr<USwitchExpression>(build(::KotlinUSwitchExpression))
            is KtBreakExpression -> expr<UBreakExpression>(build(::KotlinUBreakExpression))
            is KtContinueExpression -> expr<UContinueExpression>(build(::KotlinUContinueExpression))
            is KtReturnExpression -> expr<UReturnExpression>(build(::KotlinUReturnExpression))
            is KtThrowExpression -> expr<UThrowExpression>(build(::KotlinUThrowExpression))
            is KtBlockExpression -> expr<UBlockExpression> {
                if (expression.parent is KtFunctionLiteral
                    && expression.parent.parent is KtLambdaExpression
                    && givenParent !is KotlinULambdaExpression
                ) {
                    KotlinULambdaExpression(expression.parent.parent as KtLambdaExpression, givenParent).body
                } else
                    KotlinUBlockExpression(expression, givenParent)
            }
            is KtConstantExpression -> expr<ULiteralExpression>(build(::KotlinULiteralExpression))
            is KtTryExpression -> expr<UTryExpression>(build(::KotlinUTryExpression))
            is KtArrayAccessExpression -> expr<UArrayAccessExpression>(build(::KotlinUArrayAccessExpression))
            is KtLambdaExpression -> expr<ULambdaExpression>(build(::KotlinULambdaExpression))
            is KtBinaryExpressionWithTypeRHS -> expr<UBinaryExpressionWithType>(build(::KotlinUBinaryExpressionWithType))
            is KtClassOrObject -> expr<UDeclarationsExpression> {
                expression.toLightClass()?.let { lightClass ->
                    KotlinUDeclarationsExpression(givenParent).apply {
                        declarations = listOf(KotlinUClass.create(lightClass, this))
                    }
                } ?: UastEmptyExpression(givenParent)
            }
            is KtFunction -> if (expression.name.isNullOrEmpty()) {
                expr<ULambdaExpression>(build(::createLocalFunctionLambdaExpression))
            }
            else {
                expr<UDeclarationsExpression>(build(::createLocalFunctionDeclaration))
            }
            is KtAnnotatedExpression -> {
                expression.baseExpression
                    ?.let { convertExpression(it, givenParent, requiredType) }
                    ?: expr<UExpression>(build(::UnknownKotlinExpression))
            }

            else -> expr<UExpression>(build(::UnknownKotlinExpression))
        }}
    }

    internal fun convertWhenCondition(condition: KtWhenCondition,
                                      givenParent: UElement?,
                                      requiredType: Array<out Class<out UElement>>
    ): UExpression? {
        return with(requiredType) {
            when (condition) {
                is KtWhenConditionInRange -> expr<UBinaryExpression> {
                    KotlinCustomUBinaryExpression(condition, givenParent).apply {
                        leftOperand = KotlinStringUSimpleReferenceExpression("it", this)
                        operator = when {
                            condition.isNegated -> KotlinBinaryOperators.NOT_IN
                            else -> KotlinBinaryOperators.IN
                        }
                        rightOperand = KotlinConverter.convertOrEmpty(condition.rangeExpression, this)
                    }
                }
                is KtWhenConditionIsPattern -> expr<UBinaryExpression> {
                    KotlinCustomUBinaryExpressionWithType(condition, givenParent).apply {
                        operand = KotlinStringUSimpleReferenceExpression("it", this)
                        operationKind = when {
                            condition.isNegated -> KotlinBinaryExpressionWithTypeKinds.NEGATED_INSTANCE_CHECK
                            else -> UastBinaryExpressionWithTypeKind.InstanceCheck.INSTANCE
                        }
                        val typeRef = condition.typeReference
                        typeReference = typeRef?.let {
                            LazyKotlinUTypeReferenceExpression(it, this) { typeRef.toPsiType(this, boxed = true) }
                        }
                    }
                }

                is KtWhenConditionWithExpression ->
                    condition.expression?.let { KotlinConverter.convertExpression(it, givenParent, requiredType) }

                else -> expr<UExpression> { UastEmptyExpression(givenParent) }
            }
        }
    }

    private fun convertEnumEntry(original: KtEnumEntry, givenParent: UElement?): UElement? {
        return LightClassUtil.getLightClassBackingField(original)?.let { psiField ->
            if (psiField is KtLightField && psiField is PsiEnumConstant) {
                KotlinUEnumConstant(psiField, psiField.kotlinOrigin, givenParent)
            } else {
                null
            }
        }
    }


    internal fun convertDeclaration(
        element: PsiElement,
        givenParent: UElement?,
        expectedTypes: Array<out Class<out UElement>>
    ): UElement? {
        val original = element.originalElement

        fun <P : PsiElement> build(ctor: (P, UElement?) -> UElement): () -> UElement? = {
            @Suppress("UNCHECKED_CAST")
            ctor(original as P, givenParent)
        }

        fun <P : PsiElement, K : KtElement> buildKt(ktElement: K, ctor: (P, K, UElement?) -> UElement): () -> UElement? = {
            @Suppress("UNCHECKED_CAST")
            ctor(original as P, ktElement, givenParent)
        }

        fun <P : PsiElement, K : KtElement> buildKtOpt(ktElement: K?, ctor: (P, K?, UElement?) -> UElement): () -> UElement? = {
            @Suppress("UNCHECKED_CAST")
            ctor(original as P, ktElement, givenParent)
        }

        fun Array<out Class<out UElement>>.convertToUField(original: PsiField, kotlinOrigin: KtElement?): UElement? =
            if (original is PsiEnumConstant)
                el<UEnumConstant>(buildKtOpt(kotlinOrigin, ::KotlinUEnumConstant))
            else
                el<UField>(buildKtOpt(kotlinOrigin, ::KotlinUField))

        return with(expectedTypes) {
            when (original) {
                is KtLightMethod -> el<UMethod>(build(KotlinUMethod::create))
                is UastFakeLightMethod -> el<UMethod> {
                    val ktFunction = original.original
                    if (ktFunction.isLocal)
                        convertDeclaration(ktFunction, givenParent, expectedTypes)
                    else
                        KotlinUMethodWithFakeLightDelegate(ktFunction, original, givenParent)
                }
                is UastFakeLightPrimaryConstructor ->
                    convertFakeLightConstructorAlternatices(original, givenParent, expectedTypes).firstOrNull()
                is KtLightClass -> when (original.kotlinOrigin) {
                    is KtEnumEntry -> el<UEnumConstant> {
                        convertEnumEntry(original.kotlinOrigin as KtEnumEntry, givenParent)
                    }
                    else -> el<UClass> { KotlinUClass.create(original, givenParent) }
                }
                is KtLightField -> convertToUField(original, original.kotlinOrigin)
                is KtLightFieldForSourceDeclarationSupport ->
                    // KtLightFieldForDecompiledDeclaration is not a KtLightField
                    convertToUField(original, original.kotlinOrigin)
                is KtLightParameter -> el<UParameter>(buildKtOpt(original.kotlinOrigin, ::KotlinUParameter))
                is UastKotlinPsiParameter -> el<UParameter>(buildKt(original.ktParameter, ::KotlinUParameter))
                is UastKotlinPsiParameterBase<*> -> el<UParameter> {
                    original.ktOrigin.safeAs<KtTypeReference>()?.let { convertReceiverParameter(it) }
                }
                is UastKotlinPsiVariable -> el<ULocalVariable>(buildKt(original.ktElement, ::KotlinULocalVariable))

                is KtEnumEntry -> el<UEnumConstant> {
                    convertEnumEntry(original, givenParent)
                }
                is KtClassOrObject -> convertClassOrObject(original, givenParent, this).firstOrNull()
                is KtFunction ->
                    if (original.isLocal) {
                        el<ULambdaExpression> {
                            val parent = original.parent
                            if (parent is KtLambdaExpression) {
                                KotlinULambdaExpression(parent, givenParent) // your parent is the ULambdaExpression
                            } else if (original.name.isNullOrEmpty()) {
                                createLocalFunctionLambdaExpression(original, givenParent)
                            } else {
                                val uDeclarationsExpression = createLocalFunctionDeclaration(original, givenParent)
                                val localFunctionVar = uDeclarationsExpression.declarations.single() as KotlinLocalFunctionUVariable
                                localFunctionVar.uastInitializer
                            }
                        }
                    } else {
                        el<UMethod> {
                            val lightMethod = LightClassUtil.getLightClassMethod(original)
                            if (lightMethod != null)
                                convertDeclaration(lightMethod, givenParent, expectedTypes)
                            else {
                                val ktLightClass = getLightClassForFakeMethod(original) ?: return null
                                KotlinUMethodWithFakeLightDelegate(original, ktLightClass, givenParent)
                            }
                        }
                    }

                is KtPropertyAccessor -> el<UMethod> {
                    val lightMethod = LightClassUtil.getLightClassAccessorMethod(original) ?: return null
                    convertDeclaration(lightMethod, givenParent, expectedTypes)
                }

                is KtProperty ->
                    if (original.isLocal) {
                        KotlinConverter.convertPsiElement(original, givenParent, expectedTypes)
                    } else {
                        convertNonLocalProperty(original, givenParent, expectedTypes).firstOrNull()
                    }

                is KtParameter -> convertParameter(original, givenParent, this).firstOrNull()

                is KtFile -> convertKtFile(original, givenParent, this).firstOrNull()
                is FakeFileForLightClass -> el<UFile> { KotlinUFile(original.navigationElement) }
                is KtAnnotationEntry -> el<UAnnotation>(build(::KotlinUAnnotation))
                is KtCallExpression ->
                    if (expectedTypes.isAssignableFrom(KotlinUNestedAnnotation::class.java) && !expectedTypes.isAssignableFrom(UCallExpression::class.java)) {
                        el<UAnnotation> { KotlinUNestedAnnotation.tryCreate(original, givenParent) }
                    } else null
                is KtLightAnnotationForSourceEntry -> convertDeclarationOrElement(original.kotlinOrigin, givenParent, expectedTypes)
                is KtDelegatedSuperTypeEntry -> el<KotlinSupertypeDelegationUExpression> {
                    KotlinSupertypeDelegationUExpression(original, givenParent)
                }
                else -> null
            }
        }
    }

    internal fun convertFakeLightConstructorAlternatices(
        original: UastFakeLightPrimaryConstructor,
        givenParent: UElement?,
        expectedTypes: Array<out Class<out UElement>>
    ): Sequence<UElement> {
        return expectedTypes.accommodate(
            alternative { convertDeclaration(original.original, givenParent, expectedTypes) as? UClass },
            alternative { KotlinConstructorUMethod(original.original, original, original.original, givenParent) }
        )
    }

    private fun getLightClassForFakeMethod(original: KtFunction): KtLightClass? {
        if (original.isLocal) return null
        return getContainingLightClass(original)
    }

    fun convertDeclarationOrElement(
        element: PsiElement,
        givenParent: UElement?,
        expectedTypes: Array<out Class<out UElement>>
    ): UElement? {
        return if (element is UElement) element
        else convertDeclaration(element, givenParent, expectedTypes)
            ?: KotlinConverter.convertPsiElement(element, givenParent, expectedTypes)
    }

    private fun convertToPropertyAlternatives(
        methods: LightClassUtil.PropertyAccessorsPsiMethods?,
        givenParent: UElement?
    ): Array<UElementAlternative<*>> = if (methods != null) arrayOf(
        alternative { methods.backingField?.let { KotlinUField(it, (it as? KtLightElement<*, *>)?.kotlinOrigin, givenParent) } },
        alternative { methods.getter?.let { convertDeclaration(it, givenParent, arrayOf(UMethod::class.java)) as? UMethod } },
        alternative { methods.setter?.let { convertDeclaration(it, givenParent, arrayOf(UMethod::class.java)) as? UMethod } }
    ) else emptyArray()

    fun convertNonLocalProperty(
        property: KtProperty,
        givenParent: UElement?,
        expectedTypes: Array<out Class<out UElement>>
    ): Sequence<UElement> =
        expectedTypes.accommodate(*convertToPropertyAlternatives(LightClassUtil.getLightClassPropertyMethods(property), givenParent))


    fun convertParameter(
        element: KtParameter,
        givenParent: UElement?,
        expectedTypes: Array<out Class<out UElement>>
    ): Sequence<UElement> = expectedTypes.accommodate(
        alternative uParam@{
            val lightMethod = when (val ownerFunction = element.ownerFunction) {
                is KtFunction -> LightClassUtil.getLightClassMethod(ownerFunction)
                    ?: getLightClassForFakeMethod(ownerFunction)
                        ?.takeIf { !it.isAnnotationType }
                        ?.let { UastFakeLightMethod(ownerFunction, it) }
                is KtPropertyAccessor -> LightClassUtil.getLightClassAccessorMethod(ownerFunction)
                else -> null
            } ?: return@uParam null
            val lightParameter = lightMethod.parameterList.parameters.find { it.name == element.name } ?: return@uParam null
            KotlinUParameter(lightParameter, element, givenParent)
        },
        alternative catch@{
            val uCatchClause = element.parent?.parent?.safeAs<KtCatchClause>()?.toUElementOfType<UCatchClause>() ?: return@catch null
            uCatchClause.parameters.firstOrNull { it.sourcePsi == element }
        },
        *convertToPropertyAlternatives(LightClassUtil.getLightClassPropertyMethods(element), givenParent)
    )

    fun convertClassOrObject(
        element: KtClassOrObject,
        givenParent: UElement?,
        expectedTypes: Array<out Class<out UElement>>
    ): Sequence<UElement> {
        val ktLightClass = element.toLightClass() ?: return emptySequence()
        val uClass = KotlinUClass.create(ktLightClass, givenParent)
        return expectedTypes.accommodate(
            alternative { uClass },
            alternative primaryConstructor@{
                val primaryConstructor = element.primaryConstructor ?: return@primaryConstructor null
                uClass.methods.asSequence()
                    .filter { it.sourcePsi == primaryConstructor }
                    .firstOrNull()
            }
        )
    }

    fun convertKtFile(
        element: KtFile,
        givenParent: UElement?,
        requiredTypes: Array<out Class<out UElement>>
    ): Sequence<UElement> = requiredTypes.accommodate(
        alternative { KotlinUFile(element) },
        alternative { element.findFacadeClass()?.let { KotlinUClass.create(it, givenParent) } }
    )


    internal fun convertOrEmpty(expression: KtExpression?, parent: UElement?): UExpression {
        return expression?.let { convertExpression(it, parent, DEFAULT_EXPRESSION_TYPES_LIST) } ?: UastEmptyExpression(parent)
    }

    internal fun convertOrNull(expression: KtExpression?, parent: UElement?): UExpression? {
        return if (expression != null) convertExpression(expression, parent, DEFAULT_EXPRESSION_TYPES_LIST) else null
    }

    internal fun KtPsiFactory.createAnalyzableExpression(text: String, context: PsiElement): KtExpression =
            createAnalyzableProperty("val x = $text", context).initializer ?: error("Failed to create expression from text: '$text'")

    internal fun KtPsiFactory.createAnalyzableProperty(text: String, context: PsiElement): KtProperty =
            createAnalyzableDeclaration(text, context)

    internal fun <TDeclaration : KtDeclaration> KtPsiFactory.createAnalyzableDeclaration(text: String, context: PsiElement): TDeclaration {
        val file = createAnalyzableFile("dummy.kt", text, context)
        val declarations = file.declarations
        assert(declarations.size == 1) { "${declarations.size} declarations in $text" }
        @Suppress("UNCHECKED_CAST")
        return declarations.first() as TDeclaration
    }
}

private fun convertVariablesDeclaration(
        psi: KtVariableDeclaration,
        parent: UElement?
): UDeclarationsExpression {
    val declarationsExpression = parent as? KotlinUDeclarationsExpression
            ?: psi.parent.toUElementOfType<UDeclarationsExpression>() as? KotlinUDeclarationsExpression
            ?: KotlinUDeclarationsExpression(null, parent, psi)
    val parentPsiElement = parent?.javaPsi //TODO: looks weird. mb look for the first non-null `javaPsi` in `parents` ?
    val variable = KotlinUAnnotatedLocalVariable(
            UastKotlinPsiVariable.create(psi, parentPsiElement, declarationsExpression), psi, declarationsExpression) { annotationParent ->
        psi.annotationEntries.map { KotlinUAnnotation(it, annotationParent) }
    }
    return declarationsExpression.apply { declarations = listOf(variable) }
}

val kotlinUastPlugin get() = UastLanguagePlugin.getInstances().find { it.language == KotlinLanguage.INSTANCE } ?: KotlinUastLanguagePlugin()

private fun expressionTypes(requiredType: Class<out UElement>?) = requiredType?.let { arrayOf(it) } ?: DEFAULT_EXPRESSION_TYPES_LIST

private fun elementTypes(requiredType: Class<out UElement>?) = requiredType?.let { arrayOf(it) } ?: DEFAULT_TYPES_LIST

private fun <T : UElement> Array<out Class<out T>>.nonEmptyOr(default: Array<out Class<out UElement>>) = takeIf { it.isNotEmpty() }
    ?: default

private fun <U : UElement> Array<out Class<out UElement>>.accommodate(vararg makers: UElementAlternative<out U>): Sequence<UElement> {
    val makersSeq = makers.asSequence()
    return this.asSequence()
        .flatMap { requiredType -> makersSeq.filter { requiredType.isAssignableFrom(it.uType) } }
        .distinct()
        .mapNotNull { it.make.invoke() }
}

private inline fun <reified U : UElement> alternative(noinline make: () -> U?) = UElementAlternative(U::class.java, make)

private class UElementAlternative<U : UElement>(val uType: Class<U>, val make: () -> U?)
