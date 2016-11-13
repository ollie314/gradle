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

package org.gradle.api.internal.tasks.properties;

import org.gradle.api.file.FileCollection;
import org.gradle.api.internal.file.FileResolver;
import org.gradle.api.tasks.TaskOutputs;

public class NonCacheableTaskOutputPropertySpec extends AbstractTaskOutputPropertySpec implements TaskOutputFilePropertySpec {
    private final FileCollection files;

    public NonCacheableTaskOutputPropertySpec(TaskOutputs taskOutputs, String taskName, FileResolver resolver, Object paths) {
        super(taskOutputs);
        this.files = new TaskPropertyFileCollection(taskName, "output", this, resolver, paths);
    }

    @Override
    public FileCollection getPropertyFiles() {
        return files;
    }
}
