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
package com.salesforce.bazel.eclipse.classpath;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.IClasspathContainer;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaModelException;
import org.osgi.service.prefs.BackingStoreException;

import com.salesforce.bazel.eclipse.BazelPluginActivator;
import com.salesforce.bazel.eclipse.abstractions.WorkProgressMonitor;
import com.salesforce.bazel.eclipse.command.BazelCommandLineToolConfigurationException;
import com.salesforce.bazel.eclipse.command.BazelCommandManager;
import com.salesforce.bazel.eclipse.command.BazelWorkspaceCommandRunner;
import com.salesforce.bazel.eclipse.config.BazelEclipseProjectFactory;
import com.salesforce.bazel.eclipse.config.BazelEclipseProjectSupport;
import com.salesforce.bazel.eclipse.model.AspectOutputJarSet;
import com.salesforce.bazel.eclipse.model.AspectPackageInfo;
import com.salesforce.bazel.eclipse.model.BazelMarkerDetails;
import com.salesforce.bazel.eclipse.model.BazelWorkspace;
import com.salesforce.bazel.eclipse.runtime.api.ResourceHelper;
import com.salesforce.bazel.eclipse.runtime.impl.EclipseWorkProgressMonitor;

/**
 * Computes the classpath for a Bazel package and provides it to the JDT tooling in Eclipse.
 */
public class BazelClasspathContainer implements IClasspathContainer {
    public static final String CONTAINER_NAME = "com.salesforce.bazel.eclipse.BAZEL_CONTAINER";
    
    // TODO make classpath cache timeout configurable
    private static final long CLASSPATH_CACHE_TIMEOUT_MS = 30000; 

    private final IPath eclipseProjectPath;
    private final IProject eclipseProject;
    private final String eclipseProjectName;
    private final boolean eclipseProjectIsRoot;
    private final ResourceHelper resourceHelper;
    
    private IClasspathEntry[] cachedEntries;
    private long cachePutTimeMillis = 0;
    
    private static List<BazelClasspathContainer> instances = new ArrayList<>();
    
    private ImplicitDependencyHelper implicitDependencyHelper = new ImplicitDependencyHelper();
    
    public BazelClasspathContainer(IProject eclipseProject)
            throws IOException, InterruptedException, BackingStoreException, JavaModelException,
            BazelCommandLineToolConfigurationException {
        this(eclipseProject, BazelPluginActivator.getResourceHelper());
    }
    
    BazelClasspathContainer(IProject eclipseProject, ResourceHelper resourceHelper)
            throws IOException, InterruptedException, BackingStoreException, JavaModelException,
            BazelCommandLineToolConfigurationException {
        this.eclipseProject = eclipseProject;
        this.eclipseProjectName = eclipseProject.getName();
        this.eclipseProjectPath = eclipseProject.getLocation();
        this.eclipseProjectIsRoot = resourceHelper.isBazelRootProject(eclipseProject);
        this.resourceHelper = resourceHelper;
        
        instances.add(this);
    }
    
    public static void clean() {
        for (BazelClasspathContainer instance : instances) {
            instance.cachedEntries = null;
            instance.cachePutTimeMillis = 0;
        }
    }

    @Override
    public IClasspathEntry[] getClasspathEntries() {
        // sanity check
        if (!BazelPluginActivator.hasBazelWorkspaceRootDirectory()) {
            throw new IllegalStateException("Attempt to retrieve the classpath of a Bazel Java project prior to setting up the Bazel workspace.");
        }

        boolean foundCachedEntries = false;
        boolean isImport = false;
        
        /**
         * Observed behavior of Eclipse is that this method can get called multiple times before the first invocation completes, therefore 
         * the cache is not as effective as it could be. Synchronize on this instance such that the first invocation completes and populates
         * the cache before the subsequent calls are allowed to proceed.
         */
        synchronized (this) {
        
            if (this.cachedEntries != null) {
                long now = System.currentTimeMillis();
                if ((now - this.cachePutTimeMillis) > CLASSPATH_CACHE_TIMEOUT_MS) {
                    this.cachedEntries = null;
                } else {
                    foundCachedEntries = true;
                    if (BazelEclipseProjectFactory.importInProgress.get()) {
                        // classpath computation is iterative right now during import, each project's classpath is computed many times. 
                        // earlier in the import process, project refs might be brought in as jars because the associated project
                        //   may not have been imported yet. 
                        // by not caching during import, the classpath is continually recomputed and eventually arrives in the right state
                        BazelPluginActivator.debug("  Recomputing classpath for project "+eclipseProjectName+" because we are in an import operation.");
                        isImport = true;
                    } else {
                        BazelPluginActivator.debug("  Using cached classpath for project "+eclipseProjectName);
                        return this.cachedEntries;
                    }
                }
            }
    
            // TODO figure out a way to get access to an Eclipse progress monitor here
            WorkProgressMonitor progressMonitor = new EclipseWorkProgressMonitor(null);
    
            if (this.eclipseProjectIsRoot) {
                // this project is the artificial container to hold Bazel workspace scoped assets (e.g. the WORKSPACE file)
                return new IClasspathEntry[] {};
            }
    
            BazelPluginActivator.info("Computing classpath for project "+eclipseProjectName+" (cached entries: "+foundCachedEntries+", is import: "+isImport+")");
    
            List<IClasspathEntry> classpathEntries = new ArrayList<>();
            Set<IPath> projectsAddedToClasspath = new HashSet<>();
    
            BazelWorkspace bazelWorkspace = BazelPluginActivator.getBazelWorkspace();
            BazelCommandManager commandFacade = BazelPluginActivator.getBazelCommandManager();
            BazelWorkspaceCommandRunner bazelWorkspaceCmdRunner = commandFacade.getWorkspaceCommandRunner(bazelWorkspace);
            
            try {
                IProject eclipseIProject = eclipseProject.getProject();
                List<String> bazelTargetsForProject = BazelEclipseProjectSupport.getBazelTargetsForEclipseProject(eclipseIProject, false);
                
                Map<String, AspectPackageInfo> packageInfos = bazelWorkspaceCmdRunner.getAspectPackageInfos(
                    eclipseIProject.getName(), bazelTargetsForProject, progressMonitor, "getClasspathEntries");
    
                for (AspectPackageInfo packageInfo : packageInfos.values()) {
                    IJavaProject otherProject = getSourceProjectForSourcePaths(bazelWorkspaceCmdRunner, packageInfo.getSources());
                    
                    if (otherProject == null) {
                        // no project found that houses the sources of this bazel target, add the jars to the classpath
                        // this means that this is an external jar, or a jar produced by a bazel target that was not imported
                        for (AspectOutputJarSet jarSet : packageInfo.getGeneratedJars()) {
                            IClasspathEntry cpEntry = jarsToClasspathEntry(bazelWorkspace, progressMonitor, jarSet); 
                            if (cpEntry != null) {
                                classpathEntries.add(cpEntry);
                            } else {
                                // there was a problem with the aspect computation, this might resolve itself if we recompute it
                                bazelWorkspaceCmdRunner.flushAspectInfoCache(bazelTargetsForProject);
                            }
                        }
                        for (AspectOutputJarSet jarSet : packageInfo.getJars()) {
                            IClasspathEntry cpEntry = jarsToClasspathEntry(bazelWorkspace, progressMonitor, jarSet);
                            if (cpEntry != null) {
                                classpathEntries.add(cpEntry);
                            } else {
                                // there was a problem with the aspect computation, this might resolve itself if we recompute it
                                bazelWorkspaceCmdRunner.flushAspectInfoCache(bazelTargetsForProject);
                            }
                        }
                                                
                    } else if (eclipseProject.getProject().getFullPath().equals(otherProject.getProject().getFullPath())) {
                        // the project referenced is actually the the current project that this classpath container is for
                        
                        // some rule types have hidden dependencies that we need to add
                        // if our Eclipse project has any of those rules, we need to add in the dependencies to our classpath
                        Set<IClasspathEntry> implicitDeps = implicitDependencyHelper.computeImplicitDependencies(eclipseIProject, bazelWorkspace, packageInfo);
                        classpathEntries.addAll(implicitDeps);
                        
                    } else {
                        // otherProject != null
                        // add the referenced project to the classpath, directly as a project classpath entry
                        IPath projectFullPath = otherProject.getProject().getFullPath();
                        if (!projectsAddedToClasspath.contains(projectFullPath)) {
                            classpathEntries.add(BazelPluginActivator.getJavaCoreHelper().newProjectEntry(projectFullPath));
                        }
                        projectsAddedToClasspath.add(projectFullPath);
                        
                        // now make a project reference between this project and the other project; this allows for features like
                        // code refactoring across projects to work correctly
                        addProjectReference(eclipseIProject, otherProject.getProject());
                    }
                }
            } catch (IOException | InterruptedException e) {
                BazelPluginActivator.error("Unable to compute classpath containers entries for project "+eclipseProjectName, e);
                return new IClasspathEntry[] {};
            } catch (BazelCommandLineToolConfigurationException e) {
                BazelPluginActivator.error("Bazel not found: " + e.getMessage());
                return new IClasspathEntry[] {};
            }
    
            // cache the entries
            this.cachePutTimeMillis = System.currentTimeMillis();
            this.cachedEntries = classpathEntries.toArray(new IClasspathEntry[] {});
            BazelPluginActivator.debug("Cached the classpath for project "+eclipseProjectName);
        }
        return cachedEntries;
    }

    @Override
    public String getDescription() {
        return "Bazel Classpath Container";
    }

    @Override
    public int getKind() {
        return K_APPLICATION;
    }

    @Override
    public IPath getPath() {
        return eclipseProjectPath;
    }

    public boolean isValid() throws BackingStoreException, IOException, InterruptedException, BazelCommandLineToolConfigurationException {
        BazelWorkspace bazelWorkspace = BazelPluginActivator.getBazelWorkspace();
        File bazelWorkspaceRootDirectory = bazelWorkspace.getBazelWorkspaceRootDirectory();
        if (bazelWorkspaceRootDirectory == null) {
            return false;
        }
        BazelCommandManager bazelCommandManager = BazelPluginActivator.getBazelCommandManager();
        BazelWorkspaceCommandRunner bazelWorkspaceCmdRunner = bazelCommandManager.getWorkspaceCommandRunner(bazelWorkspace);
        
        if (bazelWorkspaceCmdRunner != null) {
            if (this.eclipseProjectIsRoot) {
                return true;
            }
            List<String> targets =
                    BazelEclipseProjectSupport.getBazelTargetsForEclipseProject(this.eclipseProject.getProject(), false);
            List<BazelMarkerDetails> details = bazelWorkspaceCmdRunner.runBazelBuild(targets, null, Collections.emptyList());
            for (BazelMarkerDetails detail : details) {
                BazelPluginActivator.error(detail.toString());
            }
            return details.isEmpty();

        }
        return false;
    }

    // INTERNAL

    /**
     * Returns the IJavaProject in the current workspace that contains at least one of the specified sources.
     */
    private IJavaProject getSourceProjectForSourcePaths(BazelWorkspaceCommandRunner bazelCommandRunner, List<String> sources) {
        for (String candidate : sources) {
            IJavaProject project = getSourceProjectForSourcePath(bazelCommandRunner, candidate);
            if (project != null) {
                return project;
            }
        }
        return null;
    }

    private IJavaProject getSourceProjectForSourcePath(BazelWorkspaceCommandRunner bazelCommandRunner, String sourcePath) {

        // TODO this code is messy, why get workspace root two different ways, and is there a better way to handle source paths?
        IWorkspaceRoot eclipseWorkspaceRoot = this.resourceHelper.getEclipseWorkspaceRoot();
        IWorkspace eclipseWorkspace = this.resourceHelper.getEclipseWorkspace();
        IWorkspaceRoot rootResource = eclipseWorkspace.getRoot();
        IProject[] projects = rootResource.getProjects();

        BazelWorkspace bazelWorkspace = BazelPluginActivator.getBazelWorkspace();
        String absoluteSourcePathString = bazelWorkspace.getBazelWorkspaceRootDirectory().getAbsolutePath() + File.separator + sourcePath;
        Path absoluteSourcePath = new File(absoluteSourcePathString).toPath();

        for (IProject project : projects) {
            IJavaProject jProject = BazelPluginActivator.getJavaCoreHelper().getJavaProjectForProject(project);
            IClasspathEntry[] classpathEntries = BazelPluginActivator.getJavaCoreHelper().getRawClasspath(jProject);
            if (classpathEntries == null) {
                BazelPluginActivator.error("No classpath entries found for project ["+jProject.getElementName()+"]");
                continue;
            }
            for (IClasspathEntry entry : classpathEntries) {
                if (entry.getEntryKind() != IClasspathEntry.CPE_SOURCE) {
                    continue;
                }
                IResource res = this.resourceHelper.findMemberInWorkspace(eclipseWorkspaceRoot, entry.getPath());
                if (res == null) {
                    continue;
                }
                IPath projectLocation = res.getLocation();
                String absProjectRoot = projectLocation.toOSString();
                if (absProjectRoot != null && !absProjectRoot.isEmpty()) {
                    if (absoluteSourcePath.startsWith(absProjectRoot)) {
                        IPath[] inclusionPatterns = entry.getInclusionPatterns();
                        IPath[] exclusionPatterns = entry.getExclusionPatterns();
                        if (!matchPatterns(absoluteSourcePath, exclusionPatterns)) {
                            if (inclusionPatterns == null || inclusionPatterns.length == 0 || matchPatterns(absoluteSourcePath, inclusionPatterns)) {
                                return jProject;
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * Globby match of file system patterns for a given path. If the path matches any of the patterns, this method
     * returns true.
     */
    private boolean matchPatterns(Path path, IPath[] patterns) {
        if (patterns != null) {
            for (IPath p : patterns) {
                PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + p.toOSString());
                if (matcher.matches(path)) {
                    return true;
                }
            }
        }
        return false;
    }

    private IClasspathEntry jarsToClasspathEntry(BazelWorkspace bazelWorkspace, WorkProgressMonitor progressMonitor, 
            AspectOutputJarSet jarSet) {
        IClasspathEntry cpEntry = null;
        File bazelOutputBase = bazelWorkspace.getBazelOutputBaseDirectory();
        File bazelExecRoot = bazelWorkspace.getBazelExecRootDirectory();
        IPath jarPath = getJarPathOnDisk(bazelOutputBase, bazelExecRoot, jarSet.getJar());
        if (jarPath != null) {
            IPath srcJarPath = getJarPathOnDisk(bazelOutputBase, bazelExecRoot, jarSet.getSrcJar());
            IPath srcJarRootPath = null;
            cpEntry = BazelPluginActivator.getJavaCoreHelper().newLibraryEntry(jarPath, srcJarPath, srcJarRootPath);
        }
        return cpEntry;
    }

    @SuppressWarnings("unused")
    private IClasspathEntry[] jarsToClasspathEntries(BazelWorkspace bazelWorkspace, WorkProgressMonitor progressMonitor, 
            Set<AspectOutputJarSet> jars) {
        IClasspathEntry[] entries = new IClasspathEntry[jars.size()];
        int i = 0;
        File bazelOutputBase = bazelWorkspace.getBazelOutputBaseDirectory();
        File bazelExecRoot = bazelWorkspace.getBazelExecRootDirectory();
        for (AspectOutputJarSet j : jars) {
            IPath jarPath = getJarPathOnDisk(bazelOutputBase, bazelExecRoot, j.getJar());
            if (jarPath != null) {
                IPath srcJarPath = getJarPathOnDisk(bazelOutputBase, bazelExecRoot, j.getSrcJar());
                IPath srcJarRootPath = null;
                entries[i] = BazelPluginActivator.getJavaCoreHelper().newLibraryEntry(jarPath, srcJarPath, srcJarRootPath);
                i++;
            }
        }
        return entries;
    }

    private IPath getJarPathOnDisk(File bazelOutputBase, File bazelExecRoot, String file) {
        if (file == null) {
            return null;
        }
        Path path = null;
        if (file.startsWith("external")) {
            path = Paths.get(bazelOutputBase.toString(), file);
        } else {
            path = Paths.get(bazelExecRoot.toString(), file);
        }

        // We have had issues with Eclipse complaining about symlinks in the Bazel output directories not being real,
        // so we resolve them before handing them back to Eclipse.
        if (Files.isSymbolicLink(path)) {
            try {
                // resolving the link will fail if the symlink does not a point to a real file
                path = Files.readSymbolicLink(path);
            } catch (IOException ex) {
                BazelPluginActivator.error("Problem adding jar to project ["+eclipseProjectName+"] because it does not exist on the filesystem: "+path);
                printDirectoryDiagnostics(path.toFile().getParentFile().getParentFile(), " ");
            }
        } else {
            // it is a normal path, check for existence
            if (!Files.exists(path)) {
                BazelPluginActivator.error("Problem adding jar to project ["+eclipseProjectName+"] because it does not exist on the filesystem: "+path);
                printDirectoryDiagnostics(path.toFile().getParentFile().getParentFile(), " ");
            }            
        }
        
        return org.eclipse.core.runtime.Path.fromOSString(path.toString());
    }

    /**
     * Creates a project reference between this project and that project.
     * The direction of reference goes from this->that
     * References are used by Eclipse code refactoring among other things. 
     */
    private void addProjectReference(IProject thisProject, IProject thatProject) {
        IProjectDescription projectDescription = this.resourceHelper.getProjectDescription(thisProject);
        IProject[] existingRefsArray = projectDescription.getReferencedProjects();
        boolean hasRef = false;
        String otherProjectName = thatProject.getName();
        for (IProject candidateRef : existingRefsArray) {
            if (candidateRef.getName().equals(otherProjectName)) {
                hasRef = true;
                break;
            }
        }
        if (!hasRef) {
            // this project does not already reference the other project, we need to add the project reference
            // as this make code refactoring across Eclipse projects work correctly (among other things)
            List<IProject> updatedRefList = new ArrayList<>(Arrays.asList(existingRefsArray));
            updatedRefList.add(thatProject.getProject());
            projectDescription.setReferencedProjects(updatedRefList.toArray(new IProject[] {}));
            resourceHelper.setProjectDescription(thisProject, projectDescription);
        }
        
    }
    
    
    private static void printDirectoryDiagnostics(File path, String indent) {
        File[] children = path.listFiles();
        System.out.println(indent+path.getAbsolutePath());
        if (children != null) {
            for (File child : children) {
                System.out.println(indent+child.getName());
                if (child.isDirectory()) {
                    printDirectoryDiagnostics(child, "   "+indent);
                }
            }
        } 
        
    }
}
