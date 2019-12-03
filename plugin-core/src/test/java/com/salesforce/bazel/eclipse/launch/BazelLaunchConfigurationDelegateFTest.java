/**
 * Copyright (c) 2019, Salesforce.com, Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the
 * following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following
 * disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the
 * following disclaimer in the documentation and/or other materials provided with the distribution.
 *
 * 3. Neither the name of Salesforce.com nor the names of its contributors may be used to endorse or promote products
 * derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
 * INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.salesforce.bazel.eclipse.launch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.salesforce.bazel.eclipse.command.mock.MockCommandBuilder.MockCommandSimulatedOutputMatcher;
import com.salesforce.bazel.eclipse.command.mock.TestBazelCommandEnvironmentFactory;
import com.salesforce.bazel.eclipse.launch.BazelLaunchConfigurationSupport.BazelLaunchConfigAttributes;
import com.salesforce.bazel.eclipse.mock.EclipseFunctionalTestEnvironmentFactory;
import com.salesforce.bazel.eclipse.mock.MockEclipse;
import com.salesforce.bazel.eclipse.mock.MockILaunch;
import com.salesforce.bazel.eclipse.mock.MockILaunchConfiguration;
import com.salesforce.bazel.eclipse.mock.MockResourceHelper;
import com.salesforce.bazel.eclipse.runtime.EclipseWorkProgressMonitor;

public class BazelLaunchConfigurationDelegateFTest {
    @Rule
    public TemporaryFolder tmpFolder = new TemporaryFolder();

    @Test
    public void testHappyRunLaunch() throws Exception {
        // setup functional test env
        MockEclipse mockEclipse = createMockEnvironment();
        ILaunchConfiguration launchConfig = createLaunchConfiguration("run");
        ILaunch launch = new MockILaunch(launchConfig);
        IProgressMonitor progress = new EclipseWorkProgressMonitor();
        addBazelCommandOutput(mockEclipse.getBazelCommandEnvironmentFactory(), "run", "bazel run result");
        BazelLaunchConfigurationDelegate delegate = mockEclipse.getLaunchDelegate();
        
        // method under test
        delegate.launch(launchConfig, "debug", launch, progress);
        
        // verify
        MockResourceHelper mockResourceHelper = mockEclipse.getMockResourceHelper();
        String[] cmdLine = mockResourceHelper.lastExecCommandLine;
        assertTrue(cmdLine[0].contains("bazel"));
        assertEquals("run", cmdLine[1]);
        assertTrue(cmdLine[2].contains("dt_socket"));
        assertTrue(cmdLine[3].contains("testvalue"));
        assertTrue(cmdLine[4].contains("testvalue"));
        assertTrue(cmdLine[5].contains("testvalue"));
        assertEquals("--", cmdLine[6]);
        assertEquals("//projects/libs/javalib0", cmdLine[7]);
    }

    @Test
    public void testHappyTestLaunch() throws Exception {
        // setup functional test env
        MockEclipse mockEclipse = createMockEnvironment();
        ILaunchConfiguration launchConfig = createLaunchConfiguration("test");
        ILaunch launch = new MockILaunch(launchConfig);
        IProgressMonitor progress = new EclipseWorkProgressMonitor();
        addBazelCommandOutput(mockEclipse.getBazelCommandEnvironmentFactory(), "test", "bazel test result");
        BazelLaunchConfigurationDelegate delegate = mockEclipse.getLaunchDelegate();
        
        // method under test
        delegate.launch(launchConfig, "debug", launch, progress);

        // verify
        MockResourceHelper mockResourceHelper = mockEclipse.getMockResourceHelper();
        String[] cmdLine = mockResourceHelper.lastExecCommandLine;
        assertTrue(cmdLine[0].contains("bazel"));
        assertEquals("test", cmdLine[1]);
        assertTrue(cmdLine[2].contains("test_output"));
        // there are a bunch of other flags passed as well 3-8
        assertTrue(cmdLine[9].contains("testvalue"));
        assertTrue(cmdLine[10].contains("testvalue"));
        assertTrue(cmdLine[11].contains("testvalue"));
        assertEquals("--", cmdLine[12]);
        assertEquals("//projects/libs/javalib0", cmdLine[13]);
    }

    // HELPERS

    private MockEclipse createMockEnvironment() throws Exception {
        File testTempDir = tmpFolder.newFolder();
        
        // during test development, it can be useful to have a stable location on disk for the Bazel workspace contents
        //testTempDir = new File("/tmp/bef/bazelws");
        //testTempDir.mkdirs();

        // create the mock Eclipse runtime in the correct state
        int numberOfJavaPackages = 2;
        boolean computeClasspaths = true; 
        MockEclipse mockEclipse = EclipseFunctionalTestEnvironmentFactory.createMockEnvironment_Imported_All_JavaPackages(testTempDir, numberOfJavaPackages, computeClasspaths);
        
        return mockEclipse;
    }

    private MockILaunchConfiguration createLaunchConfiguration(String verb) {
        MockILaunchConfiguration testConfig = new MockILaunchConfiguration();
        testConfig.attributes.put(BazelLaunchConfigAttributes.PROJECT.getAttributeName(), "javalib0");
        testConfig.attributes.put(BazelLaunchConfigAttributes.LABEL.getAttributeName(),
            "//projects/libs/javalib0");
        if ("test".equals(verb)) {
            testConfig.attributes.put(BazelLaunchConfigAttributes.TARGET_KIND.getAttributeName(), "java_test");
        } else if ("run".equals(verb)) {
            testConfig.attributes.put(BazelLaunchConfigAttributes.TARGET_KIND.getAttributeName(), "java_binary");
        }

        Map<String, String> args = new TreeMap<>();
        args.put("arg1", "testvalue1");
        args.put("arg2", "testvalue2");
        args.put("arg3", "testvalue3");
        testConfig.attributes.put(BazelLaunchConfigAttributes.INTERNAL_BAZEL_ARGS.getAttributeName(), args);

        return testConfig;
    }

    private void addBazelCommandOutput(TestBazelCommandEnvironmentFactory env, String verb, String resultLine) {
        List<String> outputLines = new ArrayList<>();
        outputLines.add(resultLine);
        List<String> errorLines = new ArrayList<>();
        
        // create a matcher such that the resultLine is only returned if a command uses the specific verb
        List<MockCommandSimulatedOutputMatcher> matchers = new ArrayList<>();
        matchers.add(new MockCommandSimulatedOutputMatcher(0, verb));
        
        env.commandBuilder.addSimulatedOutput("launcherbuildertest", outputLines, errorLines, matchers);
    }
}
