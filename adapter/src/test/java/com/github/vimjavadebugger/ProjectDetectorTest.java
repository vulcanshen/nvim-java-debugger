package com.github.vimjavadebugger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ProjectDetectorTest {

    @TempDir
    Path tempDir;

    @Test
    void detectMavenProject() throws IOException {
        new File(tempDir.toFile(), "pom.xml").createNewFile();
        ProjectDetector detector = new ProjectDetector(tempDir.toString());
        assertEquals(ProjectDetector.ProjectType.MAVEN, detector.detect());
    }

    @Test
    void detectGradleProject() throws IOException {
        new File(tempDir.toFile(), "build.gradle").createNewFile();
        ProjectDetector detector = new ProjectDetector(tempDir.toString());
        assertEquals(ProjectDetector.ProjectType.GRADLE, detector.detect());
    }

    @Test
    void detectGradleKtsProject() throws IOException {
        new File(tempDir.toFile(), "build.gradle.kts").createNewFile();
        ProjectDetector detector = new ProjectDetector(tempDir.toString());
        assertEquals(ProjectDetector.ProjectType.GRADLE, detector.detect());
    }

    @Test
    void detectSingleFileWhenNoBuildFile() {
        ProjectDetector detector = new ProjectDetector(tempDir.toString());
        assertEquals(ProjectDetector.ProjectType.SINGLE_FILE, detector.detect());
    }

    @Test
    void mavenTakesPrecedenceOverGradle() throws IOException {
        new File(tempDir.toFile(), "pom.xml").createNewFile();
        new File(tempDir.toFile(), "build.gradle").createNewFile();
        ProjectDetector detector = new ProjectDetector(tempDir.toString());
        assertEquals(ProjectDetector.ProjectType.MAVEN, detector.detect());
    }
}
