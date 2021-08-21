/*
 * Copyright 2000-2017 JetBrains s.r.o.
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
package com.intellij.java.codeInsight.daemon.lambda;

import com.intellij.codeInsight.daemon.LightDaemonAnalyzerTestCase;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.deadCode.UnusedDeclarationInspection;
import com.intellij.codeInspection.uncheckedWarnings.UncheckedWarningLocalInspection;
import com.intellij.openapi.projectRoots.JavaSdkVersion;
import com.intellij.testFramework.IdeaTestUtil;
import org.jetbrains.annotations.NotNull;

public class AaaaTest extends LightDaemonAnalyzerTestCase {
  private static final String BASE_PATH = "/codeInsight/daemonCodeAnalyzer/lambda/newMethodRef/";

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    enableInspectionTool(new UnusedDeclarationInspection());
  }

  @Override
  protected LocalInspectionTool @NotNull [] configureLocalInspectionTools() {
    return new LocalInspectionTool[]{
      new UncheckedWarningLocalInspection()
    };
  }

  public void testAaaInstanceInstance() {
    // success
    doTest();
  }

  public void testAaaInstanceInstanceVararg() {
    // success
    doTest();
  }
  public void testAaaInstanceStatic() {
    // success
    doTest();
  }
  public void testAaaInstanceStaticVararg() {
    // failure
    doTest();
  }
  public void testAaaInstanceVarargStatic() {
    // failure
    doTest();
  }

  public void testAaaInstanceVarargStaticVararg() {
    // success
    doTest();
  }

  public void testAaaStaticStaticVararg() {
    // success
    doTest();
  }

  public void testAaaConfusingErrorMessageWhileIncompatibleTypes() {
    // failure
    doTest();
  }

  private void doTest() {
    doTest(false);
  }

  private void doTest(boolean warnings) {
    IdeaTestUtil.setTestVersion(JavaSdkVersion.JDK_1_8, getModule(), getTestRootDisposable());
    doTest(BASE_PATH + getTestName(false) + ".java", warnings, false);
  }
}
