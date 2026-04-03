package com.github.vimjavadebugger;

import java.io.File;

/**
 * 偵測 Java 專案類型：Maven、Gradle 或單一 Java 檔。
 */
public class ProjectDetector {

    public enum ProjectType {
        MAVEN,
        GRADLE,
        SINGLE_FILE
    }

    private final String projectRoot;

    public ProjectDetector(String projectRoot) {
        this.projectRoot = projectRoot;
    }

    public ProjectType detect() {
        File root = new File(projectRoot);

        if (new File(root, "pom.xml").exists()) {
            return ProjectType.MAVEN;
        }

        if (new File(root, "build.gradle").exists() || new File(root, "build.gradle.kts").exists()) {
            return ProjectType.GRADLE;
        }

        return ProjectType.SINGLE_FILE;
    }
}
