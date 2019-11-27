package com.salesforce.bazel.eclipse.mock;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IProject;

import com.salesforce.bazel.eclipse.BazelPluginActivator;
import com.salesforce.bazel.eclipse.command.mock.MockBazelAspectLocation;
import com.salesforce.bazel.eclipse.command.mock.MockCommandBuilder;
import com.salesforce.bazel.eclipse.command.mock.MockCommandConsole;
import com.salesforce.bazel.eclipse.command.mock.TestBazelCommandEnvironmentFactory;
import com.salesforce.bazel.eclipse.preferences.BazelPreferencePage;
import com.salesforce.bazel.eclipse.runtime.ResourceHelper;
import com.salesforce.bazel.eclipse.test.TestBazelWorkspaceFactory;


/**
 * Primary entry point into the mocking framework for the core plugin. We only mock the small slice of Eclipse
 * that is used by our plugins. But it is enough to unit test the Core plugin which is the where we put all of our
 * code that integrates with Eclipse APIs.
 * 
 * @author plaird
 */
public class MockEclipse {

    public static final String BAZEL_WORKSPACE_NAME = "bazel-workspace";
    
    private File eclipseWorkspaceRoot;
    private File bazelWorkspaceRoot;
    private File bazelOutputBase;
    private File bazelExecutionRoot;
    private File bazelBin;

    // Eclipse mocking layer
    private MockIProjectFactory mockIProjectFactory;
    private MockIEclipsePreferences mockPrefs;
    private MockIPreferenceStore mockPrefsStore;
    private ResourceHelper mockResourceHelper;
    private MockJavaCoreHelper mockJavaCoreHelper;

    // Bazel/filesystem layer (some mocks, some real filesystem artifacts)
    private TestBazelWorkspaceFactory bazelWorkspaceFactory;
    private TestBazelCommandEnvironmentFactory bazelCommandEnvironment;

    // if this is a full functional test, we will import the Bazel workspace which will result in 
    // a list of imported IProjects, which is kept here 
    private List<IProject> importedProjectsList = new ArrayList<>();
    
    /**
     * Create a MockEclipse environment with an empty Bazel workspace. The Bazel workspace
     * has a WORKSPACE file, but that is all. For functional tests that don't do much with the Bazel
     * workspace, this setup is sufficient.
     * <p>
     * Note that after this method is complete, the MockEclipse object is configured but the import step has
     * not been run so there will be no Eclipse projects created. See EclipseFunctionalTestEnvironmentFactory
     * for convenience methods for setting up a Bazel workspace, MockEclipse, and then import of the Bazel packages. 
     */
    public MockEclipse(File testTempDir) throws Exception {
        this.bazelCommandEnvironment = new TestBazelCommandEnvironmentFactory();
        this.bazelCommandEnvironment.createTestEnvironment(testTempDir);
        
        setup(this.bazelCommandEnvironment.testWorkspace, testTempDir);
    }
    
    /**
     * Create a MockEclipse environment with a richer Bazel workspace. First, the caller will create a 
     * Bazel workspace on disk with the TestBazelWorkspaceFactory harness. Typically it will be created with
     * some Java packages and maybe some genrule packages. 
     * <p>
     * Note that after this method is complete, the MockEclipse object is configured but the import step has
     * not been run so there will be no Eclipse projects created. See EclipseFunctionalTestEnvironmentFactory
     * for convenience methods for setting up a Bazel workspace, MockEclipse, and then import of the Bazel packages. 
     */
    public MockEclipse(TestBazelWorkspaceFactory bazelWorkspace, File testTempDir) throws Exception {
        this.bazelCommandEnvironment = new TestBazelCommandEnvironmentFactory();
        this.bazelCommandEnvironment.createTestEnvironment(bazelWorkspace, testTempDir);
        
        setup(bazelWorkspace, testTempDir);
    }
    
    private void setup(TestBazelWorkspaceFactory bazelWorkspace, File testTempDir) throws Exception {
        this.bazelWorkspaceFactory = bazelWorkspace;
        this.bazelWorkspaceRoot = bazelWorkspace.dirWorkspaceRoot;
        this.bazelOutputBase = bazelWorkspace.dirOutputBase;
        this.bazelExecutionRoot = bazelWorkspace.dirExecRoot;
        this.bazelBin = bazelWorkspace.dirBazelBin;

        this.eclipseWorkspaceRoot = new File(testTempDir, "eclipse-workspace");
        this.eclipseWorkspaceRoot.mkdir();
        
        this.mockResourceHelper = new MockResourceHelper(eclipseWorkspaceRoot, this);
        this.mockPrefs = new MockIEclipsePreferences();
        this.mockPrefsStore = new MockIPreferenceStore();
        this.mockIProjectFactory = new MockIProjectFactory();
        this.mockJavaCoreHelper = new MockJavaCoreHelper();
        this.mockPrefsStore.strings.put( BazelPreferencePage.BAZEL_PATH_PREF_NAME, 
            this.bazelCommandEnvironment.bazelExecutable.getAbsolutePath());

        // initialize our plugins/feature with all the mock infrastructure
        // this simulates how our feature starts up when run inside of Eclipse
        BazelPluginActivator activator = new BazelPluginActivator();
        activator.startInternal(this.bazelCommandEnvironment.bazelAspectLocation, 
            this.bazelCommandEnvironment.commandBuilder, this.bazelCommandEnvironment.commandConsole, 
            mockResourceHelper, mockJavaCoreHelper);
        
        // At this point our plugins are wired up, the Bazel workspace is created, but the user
        // has not run a Bazel Import... wizard yet. See EclipseFunctionalTestEnvironmentFactory
        // for how to run import.
    }
    
    
    // GETTERS

    // File system
    
    public File getEclipseWorkspaceRoot() {
        return this.eclipseWorkspaceRoot;
    }

    public File getBazelWorkspaceRoot() {
        return this.bazelWorkspaceRoot;
    }

    public File getBazelOutputBase() {
        return this.bazelOutputBase;
    }

    public File getBazelExecutionRoot() {
        return this.bazelExecutionRoot;
    }

    public File getBazelBin() {
        return this.bazelBin;
    }

    public File getBazelExecutable() {
        return this.bazelCommandEnvironment.bazelExecutable.bazelExecutableFile;
    }

    // Mock Objects
    
    public MockJavaCoreHelper getJavaCoreHelper() {
        return this.mockJavaCoreHelper;
    }
    
    public MockIEclipsePreferences getMockPrefs() {
        return this.mockPrefs;
    }

    public MockIPreferenceStore getMockPrefsStore() {
        return this.mockPrefsStore;
    }

    public MockBazelAspectLocation getMockBazelAspectLocation() {
        return this.bazelCommandEnvironment.bazelAspectLocation;
    }

    public MockCommandConsole getMockCommandConsole() {
        return this.bazelCommandEnvironment.commandConsole;
    }
    
    public MockCommandBuilder getMockCommandBuilder() {
        return this.bazelCommandEnvironment.commandBuilder;
    }
    
    public MockIProjectFactory getMockIProjectFactory() {
        return this.mockIProjectFactory;
    }
    
    public List<IProject> getImportedProjectsList() {
        return this.importedProjectsList;
    }

    public IProject getImportedProject(String name) {
        for (IProject project : this.importedProjectsList) {
            String pName = project.getName();
            if (name.equals(pName)) {
                return project;
            }
        }
        return null;
    }
    
    public void setImportedProjectsList(List<IProject> importedProjectsList) {
        this.importedProjectsList = importedProjectsList;
    }

    public TestBazelWorkspaceFactory getBazelWorkspaceCreator() {
        return this.bazelWorkspaceFactory;
    }
    
    public void setBazelWorkspaceCreator(TestBazelWorkspaceFactory creator) {
        this.bazelWorkspaceFactory = creator;
    }
}
