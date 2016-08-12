/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.composite.internal;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.artifacts.DependencySubstitution;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.api.initialization.IncludedBuild;
import org.gradle.api.internal.composite.CompositeBuildContext;
import org.gradle.internal.Actions;
import org.gradle.internal.Pair;
import org.gradle.internal.component.local.model.LocalComponentArtifactMetadata;
import org.gradle.internal.component.local.model.LocalComponentMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class DefaultBuildableCompositeBuildContext implements CompositeBuildContext {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultBuildableCompositeBuildContext.class);

    private final Map<String, IncludedBuildInternal> builds = Maps.newHashMap();
    private final Set<File> configuredBuilds = Sets.newHashSet();
    private final Set<ProjectComponentIdentifier> projects = Sets.newHashSet();
    private final Set<Pair<ModuleVersionIdentifier, ProjectComponentIdentifier>> provided = Sets.newHashSet();
    private final Map<ProjectComponentIdentifier, RegisteredProject> projectMetadata = Maps.newHashMap();
    private final List<Action<DependencySubstitution>> substitutionRules = Lists.newArrayList();

    public Set<? extends IncludedBuild> getIncludedBuilds() {
        for (IncludedBuildInternal build : builds.values()) {
            ensureRegistered(build);
        }
        return Sets.newHashSet(builds.values());
    }

    @Override
    public LocalComponentMetadata getComponent(ProjectComponentIdentifier project) {
        ensureRegistered(project);
        RegisteredProject registeredProject = projectMetadata.get(project);
        return registeredProject == null ? null : registeredProject.metaData;
    }

    @Override
    public File getProjectDirectory(ProjectComponentIdentifier project) {
        RegisteredProject registeredProject = getRegisteredProject(project);
        return registeredProject.projectDirectory;
    }

    @Override
    public Set<ProjectComponentIdentifier> getAllProjects() {
        for (IncludedBuildInternal build : builds.values()) {
            ensureRegistered(build);
        }
        return projectMetadata.keySet();
    }

    public Collection<LocalComponentArtifactMetadata> getAdditionalArtifacts(ProjectComponentIdentifier project) {
        RegisteredProject registeredProject = projectMetadata.get(project);
        return registeredProject == null ? null : registeredProject.artifacts;
     }

    @Override
    public void registerBuild(String name, IncludedBuild build) {
        builds.put(name, (IncludedBuildInternal) build);
    }

    @Override
    public void registerSubstitution(ModuleVersionIdentifier moduleId, ProjectComponentIdentifier project) {
        if (projects.contains(project)) {
            String failureMessage = String.format("Project path '%s' is not unique in composite.", project.getProjectPath());
            throw new GradleException(failureMessage);
        }
        LOGGER.info("Registering project '" + project + "' in composite build. Will substitute for module '" + moduleId.getModule() + "'.");
        projects.add(project);
        provided.add(Pair.of(moduleId, project));
    }

    @Override
    public void registerSubstitution(Action<DependencySubstitution> substitutions) {
        substitutionRules.add(substitutions);
    }

    public void register(ProjectComponentIdentifier project, LocalComponentMetadata localComponentMetadata, File projectDirectory) {
        if (projectMetadata.containsKey(project)) {
            String failureMessage = String.format("Project path '%s' is not unique in composite.", project.getProjectPath());
            throw new GradleException(failureMessage);
        }
        projectMetadata.put(project, new RegisteredProject(localComponentMetadata, projectDirectory));
    }

    public void registerAdditionalArtifact(ProjectComponentIdentifier project, LocalComponentArtifactMetadata artifact) {
        getRegisteredProject(project).artifacts.add(artifact);
    }

    private RegisteredProject getRegisteredProject(ProjectComponentIdentifier project) {
        ensureRegistered(project);
        RegisteredProject registeredProject = projectMetadata.get(project);
        if (registeredProject == null) {
            throw new IllegalStateException(String.format("Requested %s which was never registered", project));
        }
        return registeredProject;
    }

    private static class RegisteredProject {
        LocalComponentMetadata metaData;
        File projectDirectory;
        Collection<LocalComponentArtifactMetadata> artifacts = Lists.newArrayList();

        public RegisteredProject(LocalComponentMetadata metaData, File projectDirectory) {
            this.metaData = metaData;
            this.projectDirectory = projectDirectory;
        }
    }

    @Override
    public Action<DependencySubstitution> getRuleAction() {
        List<Action<DependencySubstitution>> allActions = Lists.newArrayList();
        allActions.add(new CompositeBuildDependencySubstitutions(provided));
        allActions.addAll(substitutionRules);
        return Actions.composite(allActions);
    }

    @Override
    public boolean hasRules() {
        return !(provided.isEmpty() && substitutionRules.isEmpty());
    }

    private void ensureRegistered(ProjectComponentIdentifier projectComponentIdentifier) {
        if (projectMetadata.containsKey(projectComponentIdentifier)) {
            return;
        }

        IncludedBuildInternal build = getBuild(projectComponentIdentifier);
        if (build == null) {
            return;
        }
        ensureRegistered(build);
    }

    public IncludedBuildInternal getBuild(ProjectComponentIdentifier projectComponentIdentifier) {
        String[] split = projectComponentIdentifier.getProjectPath().split("::", 2);
        if (split.length == 1) {
            return null;
        }
        return builds.get(split[0]);
    }

    private void ensureRegistered(IncludedBuildInternal build) {
        File buildDir = build.getProjectDir();
        if (configuredBuilds.contains(buildDir)) {
            return;
        }
        configureBuildToRegisterDependencyMetadata(build, this);
        configuredBuilds.add(buildDir);
    }


    private void configureBuildToRegisterDependencyMetadata(IncludedBuildInternal build, CompositeBuildContext context) {
        IncludedBuildDependencyMetadataBuilder contextBuilder = new IncludedBuildDependencyMetadataBuilder(context);
        contextBuilder.build(build);
    }
}
