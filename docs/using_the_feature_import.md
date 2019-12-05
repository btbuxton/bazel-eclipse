## Using the Bazel Eclipse Feature: Workspace Import

To work on your Bazel Workspace you must first import it.
Note that there is no IDE support for creating a new Bazel Workspace from the IDE, it must already exist.

### Caveat: Only one Bazel workspace can be imported in an Eclipse workspace

At this time, the feature only supports a single Bazel workspace in an Eclipse workspace.
If you do development in multiple Bazel workspaces, you will need to have multiple Eclipse workspaces.
This issue tracks the status of this limitation: [Support multiple Bazel workspaces in a single Eclipse workspace](https://github.com/salesforce/bazel-eclipse/issues/25)

### Caveat: Only Import Conforming Java Packages

Before you proceed, please note that at this time the feature is not robust enough to build just any Bazel package.
The feature only supports what we call *Conforming Java Packages*.

Please see the [Conforming Java Packages explainer](conforming_java_packages.md) for more details.

### Steps to Import Your Bazel Workspace into Eclipse

Before you can import the Bazel workspace, you **must** run a command line build of the full workspace.
Import will fail if there are build errors in the workspace because import uses metadata computed
  from the BUILD files.

```
bazel build //...
```  

Then, in the IDE the flow for import matches the familiar Maven import process:

- *File* -> *Import...*
- *Bazel* -> *Import Bazel Workspace*
- Browse to your Bazel workspace root directory (where the *WORKSPACE* file is)
- The feature will then recursively scan that directory looking for BUILD files with *java* rules
- The scanned results will appear in the *Bazel Java Packages* tree view.
- Click the packages that you would like to have in your Eclipse Workspace.
- You do not need to have transitive closure of upstream dependencies in the Eclipse Workspace.\*
- Click *Finish*, and your Bazel packages will be imported as Eclipse projects.

\* Example: if you have two Java projects in your workspace *LibA* and *LibB*, and *LibB* depends on *LibA*. You can import just *LibB*. The feature will set the classpath correctly to consume the built *LibA* artifact.

### Next Topic: Manage your Project Configuration

The next page in our guide discusses the [Project Settings features](using_the_feature_settings.md) of the Bazel Eclipse Feature.
