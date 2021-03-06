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
 *
 * Copyright 2016 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 */
package com.salesforce.bazel.eclipse.config;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FilenameFilter;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.resources.ICommand;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.osgi.service.prefs.BackingStoreException;
import org.osgi.service.prefs.Preferences;

import com.google.common.collect.ImmutableList;
import com.salesforce.bazel.eclipse.BazelNature;
import com.salesforce.bazel.eclipse.BazelPluginActivator;
import com.salesforce.bazel.eclipse.abstractions.WorkProgressMonitor;
import com.salesforce.bazel.eclipse.builder.BazelBuilder;
import com.salesforce.bazel.eclipse.classpath.BazelClasspathContainer;
import com.salesforce.bazel.eclipse.classpath.BazelClasspathContainerInitializer;
import com.salesforce.bazel.eclipse.classpath.JavaLanguageLevelHelper;
import com.salesforce.bazel.eclipse.command.BazelCommandManager;
import com.salesforce.bazel.eclipse.command.BazelWorkspaceCommandRunner;
import com.salesforce.bazel.eclipse.logging.LogHelper;
import com.salesforce.bazel.eclipse.model.AspectPackageInfo;
import com.salesforce.bazel.eclipse.model.AspectPackageInfos;
import com.salesforce.bazel.eclipse.model.BazelLabel;
import com.salesforce.bazel.eclipse.model.BazelPackageLocation;
import com.salesforce.bazel.eclipse.model.BazelWorkspace;
import com.salesforce.bazel.eclipse.model.BazelWorkspaceCommandOptions;
import com.salesforce.bazel.eclipse.model.projectview.ProjectView;
import com.salesforce.bazel.eclipse.model.projectview.ProjectViewConstants;
import com.salesforce.bazel.eclipse.runtime.api.ResourceHelper;

/**
 * A factory class to create Eclipse projects from packages in a Bazel workspace.
 * <p>
 * TODO add test coverage.
 */
public class BazelEclipseProjectFactory {
    static final LogHelper LOG = LogHelper.log(BazelEclipseProjectFactory.class);

    static final String STANDARD_VM_CONTAINER_PREFIX = "org.eclipse.jdt.launching.JRE_CONTAINER/"
            + "org.eclipse.jdt.internal.debug.ui.launcher.StandardVMType/JavaSE-";

    /**
     * Alternate code path that we no longer use, but retained for possible future use.
     */
    private static final boolean PRECOMPUTE_ALL_ASPECTS_FOR_WORKSPACE = false;

    // signals that we are in a delicate bootstrapping operation
    public static AtomicBoolean importInProgress = new AtomicBoolean(false);

    /**
     * Imports a workspace.
     *
     * @return the list of Eclipse IProject objects created during import; by contract the first element in the list is
     *         the IProject object created for the 'bazel workspace' project node which is a special container project
     */
    public static List<IProject> importWorkspace(BazelPackageLocation bazelWorkspaceRootPackageInfo,
            List<BazelPackageLocation> selectedBazelPackages, WorkProgressMonitor progressMonitor,
          IProgressMonitor monitor) {

        String bazelWorkspaceRoot = bazelWorkspaceRootPackageInfo.getWorkspaceRootDirectory().getAbsolutePath();
        File bazelWorkspaceRootDirectory = BazelProjectHelper.getCanonicalFileSafely(new File(bazelWorkspaceRoot));

        SubMonitor subMonitor = SubMonitor.convert(monitor, selectedBazelPackages.size());
        subMonitor.setTaskName("Getting the Aspect Information for targets");
        subMonitor.split(1);

        String bazelWorkspaceName = getBazelWorkspaceName(bazelWorkspaceRoot);

        // Many collaborators need the Bazel workspace directory location, so we stash it in an accessible global location
        // currently we only support one Bazel workspace in an Eclipse workspace
        BazelPluginActivator.getInstance().setBazelWorkspaceRootDirectory(bazelWorkspaceName, bazelWorkspaceRootDirectory);

        // Set the flag that an import is in progress
        importInProgress.set(true);

        // clear out state flag in the Bazel classpath initializer in case there was a previous failed import run
        BazelClasspathContainerInitializer.isCorrupt.set(false);

        // TODO send this message to the EclipseConsole so the user actually sees it
        LOG.info("Starting import of [{}]. This may take some time, please be patient.", bazelWorkspaceName);

        // get the Workspace options (.bazelrc)
        BazelWorkspace bazelWorkspace = BazelPluginActivator.getBazelWorkspace();
        BazelWorkspaceCommandOptions options = bazelWorkspace.getBazelWorkspaceCommandOptions();
        
        // determine the Java levels
        String javacoptString = options.getContextualOption("build", "javacopt");
        int sourceLevel = JavaLanguageLevelHelper.getSourceLevelAsInt(javacoptString);
        
        // create the Eclipse project for the Bazel workspace (directory that contains the WORKSPACE file)
        IProject rootEclipseProject = createEclipseRootWorkspaceProject(bazelWorkspaceName, bazelWorkspaceRoot, sourceLevel, 
                selectedBazelPackages, monitor);
        List<IProject> importedProjectsList = new ArrayList<>();
        importedProjectsList.add(rootEclipseProject);

        // see the method level comment about this option (currently disabled)
        if (PRECOMPUTE_ALL_ASPECTS_FOR_WORKSPACE) {
            precomputeBazelAspectsForWorkspace(rootEclipseProject, selectedBazelPackages, progressMonitor);
        }

        // finally, create an Eclipse Project for each Bazel Package being imported
        subMonitor.setTaskName("Importing bazel packages: ");
        for (BazelPackageLocation childPackageInfo : selectedBazelPackages) {
            subMonitor.subTask("Importing " + childPackageInfo.getBazelPackageFSRelativePath());
            if (childPackageInfo.isWorkspaceRoot()) {
                // the workspace root node has already been created (above)
                continue;
            }
            importBazelWorkspacePackagesAsProjects(childPackageInfo, bazelWorkspaceRoot, importedProjectsList, sourceLevel);
            subMonitor.split(1);
        }

        subMonitor.done();
        // reset flag that indicates we are doing import
        importInProgress.set(false);

        return importedProjectsList;
    }
    
    public static String getBazelWorkspaceName(String bazelWorkspaceRootDirectory) {
        // TODO pull the workspace name out of the WORKSPACE file, until then use the directory name (e.g. bazel-demo)
        String bazelWorkspaceName = "workspace";
        int lastSlash = bazelWorkspaceRootDirectory.lastIndexOf(File.separator);
        if (lastSlash >= 0 && (bazelWorkspaceRootDirectory.length() - lastSlash) > 3) {
            // add the directory name to the label, if it is meaningful (>3 chars)
            bazelWorkspaceName = bazelWorkspaceRootDirectory.substring(lastSlash + 1);
        }
        return bazelWorkspaceName;
    }

    private static void importBazelWorkspacePackagesAsProjects(BazelPackageLocation packageInfo, String bazelWorkspaceRoot,
            List<IProject> importedProjectsList, int javaLanguageVersion) {
        List<String> packageSourceCodeFSPaths = new ArrayList<>();
        List<String> packageBazelTargets = new ArrayList<>();
        BazelEclipseProjectFactory.computePackageSourceCodePaths(packageInfo, packageSourceCodeFSPaths,
            packageBazelTargets);

        String eclipseProjectNameForBazelPackage = packageInfo.getBazelPackageNameLastSegment();
        URI eclipseProjectLocation = null; // let Eclipse use the default location
        String packageFSPath = packageInfo.getBazelPackageFSRelativePath();
        IProject eclipseProject = createEclipseProjectForBazelPackage(eclipseProjectNameForBazelPackage, eclipseProjectLocation,
            bazelWorkspaceRoot, packageFSPath, packageSourceCodeFSPaths, packageBazelTargets, javaLanguageVersion);

        if (eclipseProject != null) {
            boolean foundFile = linkFile(bazelWorkspaceRoot, packageFSPath, eclipseProject, "BUILD");
            if (!foundFile) {
                foundFile = linkFile(bazelWorkspaceRoot, packageFSPath, eclipseProject, "BUILD.bazel");
            }
            if (foundFile) {
                importedProjectsList.add(eclipseProject);
            } else {
                LOG.error("Could not find BUILD file for package {}", packageInfo.getBazelPackageFSRelativePath());
            }
        }
    }

    /**
     * Creates the root project that contains the WORKSPACE file.
     */
    private static IProject createEclipseRootWorkspaceProject(String bazelWorkspaceName, String bazelWorkspaceRoot, int javaLanguageVersion, 
        List<BazelPackageLocation> importedBazelPackages, IProgressMonitor monitor) {
        String rootProjectName = BazelProjectConstants.BAZELWORKSPACE_PROJECT_BASENAME+" (" + bazelWorkspaceName + ")";
        final URI eclipseProjectLocation = null; // let Eclipse use the default location
        final String packageFSPath = ""; // the root
        IProject rootProject = createEclipseProjectForBazelPackage(rootProjectName, eclipseProjectLocation, bazelWorkspaceRoot, packageFSPath,
            Collections.emptyList(), Collections.emptyList(), javaLanguageVersion);
        if (rootProject == null) {
            throw new IllegalStateException("Could not create the root workspace project. Look back in the log for more details.");            
        }                
        
        boolean linkedFile = false;
        IFile workspaceFile = BazelPluginActivator.getResourceHelper().getProjectFile(rootProject, "WORKSPACE");
        if (!workspaceFile.exists()) {
            linkedFile = linkFile(bazelWorkspaceRoot, packageFSPath, rootProject, "WORKSPACE"); 
            if (!linkedFile) {
                workspaceFile = BazelPluginActivator.getResourceHelper().getProjectFile(rootProject, "WORKSPACE.bazel");
                if (!workspaceFile.exists()) {
                    linkedFile = linkFile(bazelWorkspaceRoot, packageFSPath, rootProject, "WORKSPACE.bazel");
                }
            }
        }
        if (linkedFile) {
            writeProjectViewFile(bazelWorkspaceRoot, rootProject, importedBazelPackages, monitor);
        }
        return rootProject;
    }
    
    private static void writeProjectViewFile(String bazelWorkspaceRoot, IProject project, List<BazelPackageLocation> importedBazelPackages, IProgressMonitor monitor) {
        ProjectView projectView = new ProjectView(new File(bazelWorkspaceRoot), importedBazelPackages);
        IFile f = BazelPluginActivator.getResourceHelper().getProjectFile(project, ProjectViewConstants.PROJECT_VIEW_FILE_NAME);
        try {
            f.create(new ByteArrayInputStream(projectView.getContent().getBytes()), false, monitor);
        } catch (CoreException e) {
            throw new IllegalStateException(e);
        }        
    }
    
    /**
     * Create a Bazel Eclipse project. This method adds the natures to the project, saves the list of targets and the
     * workspace root to the project settings, make Bazel the default builder instead of ECJ and create the classpath
     * using ide build informations from Bazel.
     *
     * @param projectName
     *            the name to use for the Eclipse project, will be the label for the project in the Eclipse Project
     *            Explorer
     * @param eclipseProjectLocation
     *            typically null (let Eclipse set the location) or some other path
     * @param bazelWorkspaceRoot
     *            the absolute file system path to the Bazel workspace being imported
     * @param packageFSPath
     *            the relative path from the Bazel workspace root to the Bazel package this project belongs to
     * @param packageSourceCodeFSPaths
     *            the relative paths from the Bazel workspace root to each source directory in this project
     * @param bazelTargets
     *            the list of Bazel targets for a build/test of this project
     * @param javaLanguageVersion
     *            the Java version to use in the classpath
     */
    private static IProject createEclipseProjectForBazelPackage(String projectName, URI eclipseProjectLocation,
            String bazelWorkspaceRoot, String packageFSPath, List<String> packageSourceCodeFSPaths,
            List<String> bazelTargets, int javaLanguageVersion) {

        IProject eclipseProject = createBaseEclipseProject(projectName, eclipseProjectLocation, bazelWorkspaceRoot);
        try {
            addNatureToEclipseProject(eclipseProject, BazelNature.BAZEL_NATURE_ID);
            addNatureToEclipseProject(eclipseProject, JavaCore.NATURE_ID);
            addSettingsToEclipseProject(eclipseProject, bazelWorkspaceRoot, bazelTargets, ImmutableList.of()); // TODO pass buildFlags

            // this may throw if the user has deleted the .project file on disk while the project is open for import
            // but it will try to recover so we should catch now instead of allowing the entire flow to fail.
            // "The project description file (.project) for 'Bazel Workspace (simplejava)' was missing.  This file contains important information about the project.
            //  A new project description file has been created, but some information about the project may have been lost."
            setBuildersOnEclipseProject(eclipseProject);
        } catch (CoreException e) {
            LOG.error(e.getMessage(), e);
        } catch (BackingStoreException e) {
            LOG.error(e.getMessage(), e);
        }

        try {
            // TODO investigate using the configure() hook in the BazelNature for the configuration part of this createProject() method
            IJavaProject eclipseJavaProject =
                    BazelPluginActivator.getJavaCoreHelper().getJavaProjectForProject(eclipseProject);
            createBazelClasspathForEclipseProject(new Path(bazelWorkspaceRoot), packageFSPath, packageSourceCodeFSPaths,
                eclipseJavaProject, javaLanguageVersion);
        } catch (CoreException e) {
            LOG.error(e.getMessage(), e);
            eclipseProject = null;
        }

        return eclipseProject;
    }

    private static void addSettingsToEclipseProject(IProject eclipseProject, String bazelWorkspaceRoot,
            List<String> bazelTargets, List<String> bazelBuildFlags) throws BackingStoreException {

        Preferences eclipseProjectBazelPrefs =
                BazelPluginActivator.getResourceHelper().getProjectBazelPreferences(eclipseProject);

        int i = 0;
        for (String bazelTarget : bazelTargets) {
            eclipseProjectBazelPrefs.put(BazelEclipseProjectSupport.TARGET_PROPERTY_PREFIX + i, bazelTarget);
            i++;
        }
        eclipseProjectBazelPrefs.put(BazelEclipseProjectSupport.WORKSPACE_ROOT_PROPERTY, bazelWorkspaceRoot);
        i = 0;
        for (String bazelBuildFlag : bazelBuildFlags) {
            eclipseProjectBazelPrefs.put(BazelEclipseProjectSupport.BUILDFLAG_PROPERTY_PREFIX + i, bazelBuildFlag);
            i++;
        }
        eclipseProjectBazelPrefs.flush();
    }

    private static void setBuildersOnEclipseProject(IProject eclipseProject) throws CoreException {
        IProjectDescription eclipseProjectDescription = eclipseProject.getDescription();
        final ICommand buildCommand = eclipseProjectDescription.newCommand();
        buildCommand.setBuilderName(BazelBuilder.BUILDER_NAME);
        eclipseProjectDescription.setBuildSpec(new ICommand[] { buildCommand });
        eclipseProject.setDescription(eclipseProjectDescription, null);
    }

    private static boolean linkFile(String bazelWorkspaceRoot, String packageFSPath, IProject eclipseProject, String fileName) {
        ResourceHelper resourceHelper = BazelPluginActivator.getResourceHelper();
        boolean retval = true;

        File f = new File(new File(bazelWorkspaceRoot, packageFSPath), fileName);
        if (f.exists()) {
            IFile projectFile = resourceHelper.getProjectFile(eclipseProject, fileName);
            if (projectFile.exists()) {
                // What has happened is the user imported the Bazel workspace into Eclipse, but then deleted it from their Eclipse workspace at some point.
                // Now they are trying to import it again. Like the IntelliJ plugin we are refusing to do so, but perhaps we will
                // support this once we are convinced that we are safe to import over an old imported version of the Bazel workspace. TODO
                // For now, just bail, with a good message.
                String logMsg =
                        "You have imported this Bazel workspace into Eclipse previously, but then deleted it from your Eclipse workspace. "
                                + "This left files on the filesystem in your Eclipse workspace directory and the feature currently does not support overwriting an old imported Bazel workspace. "
                                + "\nTo import this Bazel workspace, use file system tools to delete the associated Bazel Eclipse project files from the "
                                + "Eclipse workspace directory, and then try to import again. \nFile: "
                                + projectFile.getFullPath();
                // TODO throwing this exception just writes a log message, we need a modal error popup for this error
                throw new IllegalStateException(logMsg);
            }
            try {
                resourceHelper.createFileLink(projectFile, Path.fromOSString(f.getAbsolutePath()), IResource.NONE, null);
            } catch (Exception anyE) {
                // TODO throwing this exception just writes a log message, we need a modal error popup for this error
                BazelPluginActivator.error("Failure to link file ["+f.getAbsolutePath()+"] for project ["+
                        eclipseProject.getName()+"]");
                throw anyE;
            }
        } else {
            BazelPluginActivator.error("Tried to link a non-existant file ["+f.getAbsolutePath()+"] for project ["+
                    eclipseProject.getName()+"]");
            retval = false;
        }
        return retval;
    }

    // bazelPackageFSPath: the relative path from the Bazel WORKSPACE root, to the  Bazel Package being processed
    // packageSourceCodeFSRelativePaths: the relative paths from the WORKSPACE root to the Java Source directories
    // where the Java Package structure starts
    private static void createBazelClasspathForEclipseProject(IPath bazelWorkspacePath, String bazelPackageFSPath,
            List<String> packageSourceCodeFSRelativePaths, IJavaProject eclipseProject, int javaLanguageLevel)
            throws CoreException {
        List<IClasspathEntry> classpathEntries = new LinkedList<>();
        ResourceHelper resourceHelper = BazelPluginActivator.getResourceHelper();

        for (String path : packageSourceCodeFSRelativePaths) {
            IPath realSourceDir = Path.fromOSString(bazelWorkspacePath + File.separator + path);
            IFolder projectSourceFolder =
                    createFoldersForRelativePackagePath(eclipseProject.getProject(), bazelPackageFSPath, path);
            try {
                resourceHelper.createFolderLink(projectSourceFolder, realSourceDir, IResource.NONE, null);
            } catch (Exception anyE) {
                // this can happen in degenerate cases such as source directory is the root of the project
                anyE.printStackTrace();
                continue;
            }

            IPath outputDir = null; // null is a legal value, it means use the default
            boolean isTestSource = false;
            if (path.endsWith("src/test/java")) { // NON_CONFORMING PROJECT SUPPORT
                isTestSource = true;
                outputDir = new Path(eclipseProject.getPath().toOSString() + "/testbin");
            }

            IPath sourceDir = projectSourceFolder.getFullPath();
            IClasspathEntry sourceClasspathEntry = BazelPluginActivator.getJavaCoreHelper().newSourceEntry(sourceDir, outputDir, isTestSource);
            classpathEntries.add(sourceClasspathEntry);
        }

        IClasspathEntry bazelClasspathContainerEntry = BazelPluginActivator.getJavaCoreHelper()
                .newContainerEntry(new Path(BazelClasspathContainer.CONTAINER_NAME));
        classpathEntries.add(bazelClasspathContainerEntry);

        // add in a JDK to the classpath
        String jdk;
        if (javaLanguageLevel < 9) {
            jdk = STANDARD_VM_CONTAINER_PREFIX+"1."+javaLanguageLevel;
        } else {
            jdk = STANDARD_VM_CONTAINER_PREFIX+javaLanguageLevel;
        }
        
        classpathEntries.add(BazelPluginActivator.getJavaCoreHelper().newContainerEntry(new Path(jdk)));

        IClasspathEntry[] newClasspath = classpathEntries.toArray(new IClasspathEntry[classpathEntries.size()]);
        eclipseProject.setRawClasspath(newClasspath, null);
    }

    private static IFolder createFoldersForRelativePackagePath(IProject project, String bazelPackageFSPath,
            String packageSourceCodeFSRelativePath) {

        if (!packageSourceCodeFSRelativePath.startsWith(bazelPackageFSPath)) {
            throw new IllegalStateException("src code path "+packageSourceCodeFSRelativePath+" expected to be under bazel package path "+bazelPackageFSPath);
        }

        IFolder currentFolder = null;
        if (packageSourceCodeFSRelativePath.equals(bazelPackageFSPath)) {
            currentFolder = project.getFolder(packageSourceCodeFSRelativePath);
        } else {
            String sourceDirectoryPath = packageSourceCodeFSRelativePath.substring(bazelPackageFSPath.length() + 1); // +1 for '/'
            String[] pathComponents = sourceDirectoryPath.split(File.separator);
            for (int i = 0; i < pathComponents.length; i++) {
                String pathComponent = pathComponents[i];
                if (currentFolder == null) {
                    currentFolder = project.getFolder(pathComponent);
                } else {
                    currentFolder = currentFolder.getFolder(pathComponent);
                }
                if (i < (pathComponents.length - 1)) {
                    // don't create the last folder - that happens as part of "linking" it
                    create(currentFolder);
                }
            }
        }
        return currentFolder;
    }

    private static void create(IFolder folder) {
        if (folder.exists()) {
            return;
        }
        try {
            folder.create(false, true, null);
        } catch (CoreException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static IProject createBaseEclipseProject(String eclipseProjectName, URI location,
            String bazelWorkspaceRoot) {
        ResourceHelper resourceHelper = BazelPluginActivator.getResourceHelper();
        IProgressMonitor progressMonitor = null;

        // Request the project by name, which will create a new shell IProject instance if the project doesn't already exist.
        // For this case, we expect it not to exist, but there may be a problem here if there are multiple Bazel packages
        // with the same name in different parts of the Bazel workspace (we don't support that yet).
        IProject newEclipseProject = resourceHelper.getProjectByName(eclipseProjectName);
        IProject createdEclipseProject = null;

        if (!newEclipseProject.exists()) {
            URI eclipseProjectLocation = location;
            IWorkspaceRoot workspaceRoot = resourceHelper.getEclipseWorkspaceRoot();

            // create the project description, which is initialized to:
            // 1. the given project name 2. no references to other projects 3. an empty build spec 4. an empty comment
            // to which we add the location uri
            IProjectDescription eclipseProjectDescription = resourceHelper.createProjectDescription(newEclipseProject);
            if (location != null && workspaceRoot.getLocationURI().equals(location)) {
                eclipseProjectLocation = null;
            }
            eclipseProjectDescription.setLocationURI(eclipseProjectLocation);

            try {
                createdEclipseProject = resourceHelper.createProject(newEclipseProject, eclipseProjectDescription, progressMonitor);
                if (!createdEclipseProject.isOpen()) {
                    resourceHelper.openProject(createdEclipseProject, progressMonitor);
                }
            } catch (CoreException e) {
                LOG.error(e.getMessage(), e);
                createdEclipseProject = null;
            }
        } else {
            BazelPluginActivator.error("Project ["+eclipseProjectName+"] already exists, which is unexpected. Project initialization will not occur.");
            createdEclipseProject = newEclipseProject;
        }

        return createdEclipseProject;
    }

    // TODO this code also exists in BazelProjectConfigurator, dedupe
    static void addNatureToEclipseProject(IProject eclipseProject, String nature) throws CoreException {
        if (!eclipseProject.hasNature(nature)) {
            ResourceHelper resourceHelper = BazelPluginActivator.getResourceHelper();

            IProjectDescription eclipseProjectDescription = resourceHelper.getProjectDescription(eclipseProject);
            String[] prevNatures = eclipseProjectDescription.getNatureIds();
            String[] newNatures = new String[prevNatures.length + 1];
            System.arraycopy(prevNatures, 0, newNatures, 0, prevNatures.length);
            newNatures[prevNatures.length] = nature;
            eclipseProjectDescription.setNatureIds(newNatures);

            resourceHelper.setProjectDescription(eclipseProject, eclipseProjectDescription);
        }
    }

    /**
     * This method populates the following arguments, based on the value of the specified packageNode:
     *
     * packageSourceCodeFSPaths: the relative paths, from the root of the WORKSPACE, to the start of the Java Package
     * structure directories For example, if a Java Class, com.bit.coin.Main, lives at
     * //projects/libs/bitcoin/src/main/java/com/bit/coin/Main.java, the path projects/libs/bitcoin/src/main/java is
     * added to this list
     *
     * bazelTargets: the list of Bazel labels to associate with the given packageNode
     */
    private static void computePackageSourceCodePaths(BazelPackageLocation packageNode,
            List<String> packageSourceCodeFSPaths, List<String> bazelTargets) {
        boolean foundSourceCodePaths = false;

        // add this node buildable target
        String bazelPackageRootDirectory = packageNode.getWorkspaceRootDirectory().getAbsolutePath();
        File packageDirectory = new File(packageNode.getWorkspaceRootDirectory(), packageNode.getBazelPackageFSRelativePath());

        // TODO here is where we assume that the Java project is conforming  NON_CONFORMING PROJECT SUPPORT
        // https://git.soma.salesforce.com/services/bazel-eclipse/blob/master/docs/conforming_java_packages.md
        // tracked as ISSUE #8  https://github.com/salesforce/bazel-eclipse/issues/8
        String mainSrcRelPath = packageNode.getBazelPackageFSRelativePath() + File.separator + "src" + File.separator
                + "main" + File.separator + "java";
        File mainSrcDir = new File(bazelPackageRootDirectory + File.separator + mainSrcRelPath);
        if (mainSrcDir.exists()) {
            packageSourceCodeFSPaths.add(mainSrcRelPath);
            foundSourceCodePaths = true;
        }
        String testSrcRelPath = packageNode.getBazelPackageFSRelativePath() + File.separator + "src" + File.separator
                + "test" + File.separator + "java";
        File testSrcDir = new File(bazelPackageRootDirectory + File.separator + testSrcRelPath);
        if (testSrcDir.exists()) {
            packageSourceCodeFSPaths.add(testSrcRelPath);
            foundSourceCodePaths = true;
        }
        
        // proto files are generally in the toplevel folder, lets check for those now
        if (packageDirectory.list(new BEFSourceCodeFilter()).length > 0) {
            packageSourceCodeFSPaths.add(packageNode.getBazelPackageFSRelativePath());
        }

        if (foundSourceCodePaths) {
            BazelLabel packageTarget = new BazelLabel(packageNode.getBazelPackageFSRelativePath());
            if (packageTarget.isPackageDefault()) {
                // if the label is //foo, we want foo:* so that we pick up all targets in the
                // BUILD file, instead of only the default package target
                packageTarget = packageTarget.toPackageWildcardLabel();
            }
            bazelTargets.add(packageTarget.getLabel());
        }
    }
    
    /**
     * This eventually looks for java files when we implement https://github.com/salesforce/bazel-eclipse/issues/8
     */
    private static class BEFSourceCodeFilter implements FilenameFilter {
        @Override
        public boolean accept(File dir, String name) {
            if (name.endsWith(".proto")) {
                return true;
            }
            return false;
        }
    }


    /**
     * Computes the aspects for all selected Bazel packages in the Bazel workspace during import. We were doing
     * this at an early point in the Bazel Eclipse Feature history, but this no longer is necessary. Retaining the code
     * just in case we find it useful again.
     */
    private static AspectPackageInfos precomputeBazelAspectsForWorkspace(IProject rootEclipseProject, List<BazelPackageLocation> selectedBazelPackages,
            WorkProgressMonitor progressMonitor) {
        BazelWorkspace bazelWorkspace = BazelPluginActivator.getBazelWorkspace();
        BazelCommandManager bazelCommandManager = BazelPluginActivator.getBazelCommandManager();
        BazelWorkspaceCommandRunner bazelWorkspaceCmdRunner = bazelCommandManager.getWorkspaceCommandRunner(bazelWorkspace);

        // figure out which Bazel targets will be imported, and generated AspectPackageInfos for each
        // The AspectPackageInfos have useful information that we use during import
        List<String> packageBazelTargets = new ArrayList<>();
        for (BazelPackageLocation childPackageInfo : selectedBazelPackages) {
            BazelEclipseProjectFactory.computePackageSourceCodePaths(childPackageInfo, new ArrayList<>(),
                packageBazelTargets);
        }

        // run the aspect for specified targets and get an AspectPackageInfo for each
        AspectPackageInfos aspectPackageInfos = null;
        try {
            Map<String, AspectPackageInfo> packageInfos = bazelWorkspaceCmdRunner.getAspectPackageInfos(rootEclipseProject.getName(),
                packageBazelTargets, progressMonitor, "importWorkspace");
            aspectPackageInfos = new AspectPackageInfos(packageInfos.values());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return aspectPackageInfos;
    }

}
