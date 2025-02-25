// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.gradleJava.run

import com.intellij.execution.Location
import com.intellij.openapi.module.Module
import org.jetbrains.kotlin.idea.caches.project.isNewMPPModule
import org.jetbrains.kotlin.idea.project.platform
import org.jetbrains.kotlin.platform.jvm.isJvm

class KotlinJvmTestClassGradleConfigurationProducer : AbstractKotlinTestClassGradleConfigurationProducer() {
    override val forceGradleRunner get() = false
    override val hasTestFramework get() = canRunJvmTests()
    override fun isApplicable(module: Module) = module.platform.isJvm() && !module.isNewMPPModule

    override fun getPsiClassForLocation(contextLocation: Location<*>) = getTestClassForJvm(contextLocation)
    override fun getPsiMethodForLocation(contextLocation: Location<*>) = getTestMethodForJvm(contextLocation)
}