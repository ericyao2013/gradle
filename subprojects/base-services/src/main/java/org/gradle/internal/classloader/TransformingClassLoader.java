/*
 * Copyright 2013 the original author or authors.
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

package org.gradle.internal.classloader;

import com.google.common.io.ByteStreams;
import org.apache.commons.lang.StringUtils;
import org.gradle.api.GradleException;
import org.gradle.internal.classpath.ClassPath;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.security.CodeSource;
import java.security.cert.Certificate;
import java.util.Collection;

public abstract class TransformingClassLoader extends MutableURLClassLoader {
    public TransformingClassLoader(ClassLoader parent, ClassPath classPath) {
        super(parent, classPath);
    }

    public TransformingClassLoader(ClassLoader parent, Collection<URL> urls) {
        super(parent, urls);
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        if (!shouldTransform(name)) {
            return super.findClass(name);
        }

        String resourceName = name.replace('.', '/') + ".class";
        URL resource = findResource(resourceName);
        if (resource == null) {
            throw new ClassNotFoundException(name);
        }

        try {
            byte[] bytes = loadBytecode(resource);
            bytes = transform(name, bytes);
            String packageName = StringUtils.substringBeforeLast(name, ".");
            Package p = getPackage(packageName);
            if (p == null) {
                definePackage(packageName, null, null, null, null, null, null, null);
            }
            URL codeBase = ClasspathUtil.getClasspathForResource(resource, resourceName).toURI().toURL();
            return defineClass(name, bytes, 0, bytes.length, new CodeSource(codeBase, (Certificate[]) null));
        } catch (Exception e) {
            throw new GradleException(String.format("Could not load class '%s' from %s.", name, resource), e);
        }
    }

    private byte[] loadBytecode(URL resource) throws IOException {
        InputStream inputStream = resource.openStream();
        try {
            return ByteStreams.toByteArray(inputStream);
        } finally {
            inputStream.close();
        }
    }

    protected boolean shouldTransform(String className) {
        return true;
    }

    protected abstract byte[] transform(String className, byte[] bytes);
}
