load(
    "//tools/eclipse:eclipse_plugin.bzl",
    "eclipse_plugin",
)
load("//tools:feature_version_def.bzl", "VERSION")

java_library(
    name = "plugin-model",
    srcs = glob(["src/main/java/**/*.java"]),
    visibility = ["//visibility:public"],
    deps = [

        # do not add an Eclipse deps here, this library is free from Eclipse API
        # so that we can have easily run unit tests
        # only the core plugin knows about Eclipse API

        "//plugin-libs/plugin-deps:com_google_guava",
        "//plugin-libs/plugin-deps:org_json_json",
    ],
)

eclipse_plugin(
    name = "com.salesforce.bazel.eclipse.model",
    srcs = glob(["src/main/java/**/*.java"]),
    bundle_name = "Bazel Eclipse Model Plugin",
    vendor = "Salesforce, Bazel Authors",
    version = VERSION,
    exports = 'com.salesforce.bazel.eclipse.model;version="'+VERSION+'"',
    visibility = ["//visibility:public"],
    deps = [
    "//plugin-libs/plugin-deps:com_google_guava",
    "//plugin-libs/plugin-deps:org_json_json",
    ],
)

java_test(
    name = "AspectPackageInfosTest",
    srcs = ["src/test/java/com/salesforce/bazel/eclipse/model/AspectPackageInfosTest.java",],
    deps = [
        ":plugin-model",
        "//plugin-libs/plugin-deps:com_google_guava",
    ],
)

java_test(
    name = "BazelLabelTest",
    srcs = [
        "src/main/java/com/salesforce/bazel/eclipse/model/BazelLabel.java",
        "src/test/java/com/salesforce/bazel/eclipse/model/BazelLabelTest.java",
    ],
)

java_test(
    name = "BazelMarkerDetailsTest",
    srcs = [
        "src/main/java/com/salesforce/bazel/eclipse/model/BazelLabel.java",
        "src/main/java/com/salesforce/bazel/eclipse/model/BazelMarkerDetails.java",
        "src/test/java/com/salesforce/bazel/eclipse/model/BazelMarkerDetailsTest.java",
    ],
)

java_test(
    name = "BazelOutputDirectoryBuilderTest",
    srcs = [
        "src/main/java/com/salesforce/bazel/eclipse/model/BazelLabel.java",
        "src/main/java/com/salesforce/bazel/eclipse/model/BazelOutputDirectoryBuilder.java",
        "src/main/java/com/salesforce/bazel/eclipse/model/TargetKind.java",
        "src/test/java/com/salesforce/bazel/eclipse/model/BazelOutputDirectoryBuilderTest.java"
    ],
)

java_test(
    name = "BazelOutputParserTest",
    srcs = [
        "src/main/java/com/salesforce/bazel/eclipse/model/BazelLabel.java",
        "src/main/java/com/salesforce/bazel/eclipse/model/BazelMarkerDetails.java",
        "src/main/java/com/salesforce/bazel/eclipse/model/BazelOutputParser.java",
        "src/test/java/com/salesforce/bazel/eclipse/model/BazelOutputParserTest.java",
    ],
    deps = ["//plugin-libs/plugin-deps:com_google_guava",],
)

java_test(
    name = "BazelProjectInfoTest",
    srcs = ["src/test/java/com/salesforce/bazel/eclipse/model/BazelProjectInfoTest.java"],
    deps = [
        ":plugin-model",

        "//plugin-libs/plugin-deps:com_google_guava",
        "//plugin-libs/plugin-testdeps:org_hamcrest_core",
        "//plugin-libs/plugin-testdeps:org_junit_junit",
        "//plugin-libs/plugin-testdeps:com_google_truth",
    ],
)

java_test(
    name = "BazelBuildFileHelperTest",
    srcs = ["src/test/java/com/salesforce/bazel/eclipse/model/BazelBuildFileHelperTest.java"],
    deps = [
        ":plugin-model",

        "//plugin-libs/plugin-testdeps:org_hamcrest_core",
        "//plugin-libs/plugin-testdeps:org_junit_junit",
        "//plugin-libs/plugin-testdeps:com_google_truth",
    ],
)

java_test(
    name = "BazelPackageInfoTest",
    srcs = [
        "src/main/java/com/salesforce/bazel/eclipse/model/BazelPackageInfo.java",
        "src/test/java/com/salesforce/bazel/eclipse/model/BazelPackageInfoTest.java",
        "src/main/java/com/salesforce/bazel/eclipse/model/BazelPackageLocation.java",
    ],
)

java_test(
    name = "LogHelperTest",
    srcs = ["src/test/java/com/salesforce/bazel/eclipse/logging/CaptureLoggerFacade.java",
            "src/test/java/com/salesforce/bazel/eclipse/logging/LogHelperTest.java"],
    deps = [
        ":plugin-model",

        "//plugin-libs/plugin-testdeps:org_hamcrest_core",
        "//plugin-libs/plugin-testdeps:org_junit_junit",
        "//plugin-libs/plugin-testdeps:com_google_truth",
    ],
)

java_test(
    name = "ProjectViewTest",
    srcs = [
        "src/main/java/com/salesforce/bazel/eclipse/model/BazelPackageLocation.java",
        "src/main/java/com/salesforce/bazel/eclipse/model/projectview/ProjectView.java",
        "src/main/java/com/salesforce/bazel/eclipse/model/projectview/ProjectViewPackageLocation.java",
        "src/test/java/com/salesforce/bazel/eclipse/model/projectview/ProjectViewTest.java",
    ],
)
