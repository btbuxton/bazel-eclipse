package com.salesforce.bazel.eclipse.command.internal;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.salesforce.bazel.eclipse.command.mock.MockWorkProgressMonitor;
import com.salesforce.bazel.eclipse.command.mock.TestBazelCommandEnvironmentFactory;
import com.salesforce.bazel.eclipse.test.TestBazelWorkspaceFactory;

public class BazelCommandExecutorTest {
    @Rule
    public TemporaryFolder tmpFolder = new TemporaryFolder();

    @Test
    public void testHappy_StdOut() throws Exception {
        TestBazelCommandEnvironmentFactory env = createEnv();

        List<String> emptyLines = new ArrayList<>();
        List<String> outputLines = new ArrayList<>();
        outputLines.add("result line 1");
        outputLines.add("result line 2");
        env.commandBuilder.addSimulatedOutput("testcommand1", outputLines, emptyLines);
        
        List<String> args = new ArrayList<>();
        args.add("build");
        args.add("//projects/libs/javalib0");
        
        BazelCommandExecutor executor = new BazelCommandExecutor(env.bazelExecutable.bazelExecutableFile, env.commandBuilder);
        List<String> result = executor.runBazelAndGetOutputLines(env.bazelWorkspaceCommandRunner.getBazelWorkspaceRootDirectory(), 
            new MockWorkProgressMonitor(), args, (t) -> t);
        
        assertEquals(2, result.size());
        assertEquals("result line 1", result.get(0));
        assertEquals("result line 2", result.get(1));
    }

    @Test
    public void testHappy_StdErr() throws Exception {
        TestBazelCommandEnvironmentFactory env = createEnv();

        List<String> emptyLines = new ArrayList<>();
        List<String> errLines = new ArrayList<>();
        errLines.add("result line 1");
        errLines.add("result line 2");
        env.commandBuilder.addSimulatedOutput("testcommand1", emptyLines, errLines);
        
        List<String> args = new ArrayList<>();
        args.add("build");
        args.add("//projects/libs/javalib0");
        
        BazelCommandExecutor executor = new BazelCommandExecutor(env.bazelExecutable.bazelExecutableFile, env.commandBuilder);
        List<String> result = executor.runBazelAndGetErrorLines(env.bazelWorkspaceCommandRunner.getBazelWorkspaceRootDirectory(), 
            new MockWorkProgressMonitor(), args, (t) -> t);
        
        assertEquals(2, result.size());
        assertEquals("result line 1", result.get(0));
        assertEquals("result line 2", result.get(1));
    }
    
    @Test
    public void testStripInfo() {
        List<String> outputLines = new ArrayList<>();
        outputLines.add("result line 1");
        outputLines.add("result line 2");
        outputLines.add("INFO: result line 3");
        outputLines.add("result line 4");

        List<String> result = BazelCommandExecutor.stripInfoLines(outputLines);
        assertEquals(3, result.size());
        assertEquals("result line 1", result.get(0));
        assertEquals("result line 2", result.get(1));
        assertEquals("result line 4", result.get(2));
    }
    
    
    // INTERNAL
    
    private TestBazelCommandEnvironmentFactory createEnv() throws Exception {
        File testDir = tmpFolder.newFolder();
        File workspaceDir = new File(testDir, "bazel-workspace");
        workspaceDir.mkdirs();
        File outputbaseDir = new File(testDir, "outputbase");
        outputbaseDir.mkdirs();
        TestBazelWorkspaceFactory workspace = new TestBazelWorkspaceFactory(workspaceDir, outputbaseDir).javaPackages(1).build();
        TestBazelCommandEnvironmentFactory env = new TestBazelCommandEnvironmentFactory();
        env.createTestEnvironment(workspace, testDir);
        
        return env;
    }
}
